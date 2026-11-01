package com.btcsignal.app.data.binance

import android.os.Handler
import android.os.Looper
import com.btcsignal.app.data.model.Candle
import okhttp3.*
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

enum class ConnectionState { CONNECTING, CONNECTED, DISCONNECTED, ERROR }

interface BinanceStreamListener {
    /** Called on every kline update, closed or not; [candle].isClosed tells you which. */
    fun onKlineUpdate(candle: Candle)
    fun onConnectionStateChanged(state: ConnectionState)
    fun onError(message: String)
}

/**
 * Subscribes to Binance's public combined kline WebSocket stream for BTCUSDT 1m
 * candles (spec section 26). Handles disconnect/reconnect with exponential backoff and
 * de-duplicates repeated updates for the same still-forming candle upstream (the caller,
 * MarketDataStore/CandleAggregator, only acts on isClosed==true events for candle
 * construction; intra-candle updates are only used to drive the Live Price display).
 */
class BinanceWebSocketClient(
    private val listener: BinanceStreamListener,
    private val symbol: String = "btcusdt",
    private val interval: String = "1m"
) {
    private var client: OkHttpClient? = null
    private var webSocket: WebSocket? = null
    private val shouldRun = AtomicBoolean(false)
    private var reconnectAttempts = 0
    private val mainHandler = Handler(Looper.getMainLooper())
    private var lastMessageAtMillis: Long = 0L

    private val staleCheckRunnable = object : Runnable {
        override fun run() {
            if (shouldRun.get() && lastMessageAtMillis > 0 &&
                System.currentTimeMillis() - lastMessageAtMillis > STALE_THRESHOLD_MILLIS
            ) {
                listener.onError("WebSocket stream stale (no message for >${STALE_THRESHOLD_MILLIS / 1000}s), reconnecting")
                reconnect()
            }
            mainHandler.postDelayed(this, STALE_CHECK_INTERVAL_MILLIS)
        }
    }

    fun connect() {
        if (shouldRun.get()) return
        shouldRun.set(true)
        reconnectAttempts = 0
        openSocket()
        mainHandler.postDelayed(staleCheckRunnable, STALE_CHECK_INTERVAL_MILLIS)
    }

    fun disconnect() {
        shouldRun.set(false)
        mainHandler.removeCallbacks(staleCheckRunnable)
        webSocket?.close(1000, "Client disconnect")
        webSocket = null
        listener.onConnectionStateChanged(ConnectionState.DISCONNECTED)
    }

    private fun openSocket() {
        listener.onConnectionStateChanged(ConnectionState.CONNECTING)
        val okClient = OkHttpClient.Builder()
            .pingInterval(20, TimeUnit.SECONDS)
            .build()
        client = okClient
        val url = "wss://stream.binance.com:9443/ws/$symbol@kline_$interval"
        val request = Request.Builder().url(url).build()
        webSocket = okClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                reconnectAttempts = 0
                lastMessageAtMillis = System.currentTimeMillis()
                listener.onConnectionStateChanged(ConnectionState.CONNECTED)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                lastMessageAtMillis = System.currentTimeMillis()
                try {
                    parseAndDispatch(text)
                } catch (e: Exception) {
                    listener.onError("Failed to parse kline message: ${e.message}")
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                listener.onConnectionStateChanged(ConnectionState.ERROR)
                listener.onError("WebSocket failure: ${t.message}")
                if (shouldRun.get()) reconnect()
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (shouldRun.get()) {
                    listener.onConnectionStateChanged(ConnectionState.DISCONNECTED)
                    reconnect()
                }
            }
        })
    }

    private fun parseAndDispatch(text: String) {
        val root = JSONObject(text)
        val k = root.getJSONObject("k")
        val candle = Candle(
            openTimeMillis = k.getLong("t"),
            open = k.getString("o").toDouble(),
            high = k.getString("h").toDouble(),
            low = k.getString("l").toDouble(),
            close = k.getString("c").toDouble(),
            volume = k.getString("v").toDouble(),
            closeTimeMillis = k.getLong("T"),
            isClosed = k.getBoolean("x")
        )
        mainHandler.post { listener.onKlineUpdate(candle) }
    }

    private fun reconnect() {
        webSocket?.close(1000, "Reconnecting")
        webSocket = null
        if (!shouldRun.get()) return
        reconnectAttempts += 1
        val delayMillis = minOf(BASE_RECONNECT_DELAY_MILLIS * (1L shl minOf(reconnectAttempts, 5)), MAX_RECONNECT_DELAY_MILLIS)
        mainHandler.postDelayed({ if (shouldRun.get()) openSocket() }, delayMillis)
    }

    companion object {
        private const val BASE_RECONNECT_DELAY_MILLIS = 1000L
        private const val MAX_RECONNECT_DELAY_MILLIS = 30_000L
        // Was 90s/15s: on a stalled-but-not-closed socket (common over VPN/flaky mobile
        // networks — no onFailure/onClosed callback fires, so reconnect() never runs on
        // its own) up to 90 real seconds of live BTCUSDT price movement could go
        // unlogged, which the "Price Move Since Candle Open" chart then drew as one
        // long straight interpolated line between the last tick before the stall and
        // the first tick after — reported as a flat, inaccurate-looking purple segment.
        // Binance normally pushes kline updates roughly every 1-2s, so a much shorter
        // threshold still comfortably avoids false positives on a healthy connection.
        private const val STALE_THRESHOLD_MILLIS = 20_000L
        private const val STALE_CHECK_INTERVAL_MILLIS = 5_000L
    }
}
