package com.btcsignal.app.data.binance

import com.btcsignal.app.data.model.Candle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Thin wrapper over Binance's PUBLIC market-data REST API only (spec sections 3, 26, 45:
 * no private/trading endpoints, no API key, no order placement anywhere in this app).
 */
class BinanceRestClient(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()
) {
    companion object {
        private const val BASE_URL = "https://api.binance.com/api/v3/klines"
        private const val MAX_LIMIT = 1000
    }

    /**
     * Fetches CLOSED 1-minute klines for [symbol] between [startTimeMillis] (inclusive)
     * and [endTimeMillis] (exclusive), paging in batches of 1000 as Binance requires. A
     * candle that has not actually closed yet (closeTimeMillis in the future relative to
     * wall-clock time) is never returned, however close [endTimeMillis] is to the present
     * moment - Binance returns the still-forming "current" candle when a query window
     * reaches into the present, with OHLC values that keep changing until real close, and
     * this is the one place that guarantee is enforced so every caller (warm-up, gap
     * resync, backtest) gets a genuine no-look-ahead guarantee for free instead of each
     * needing to separately re-derive "is this candle actually closed".
     *
     * [onPageFetched], if provided, is invoked after each page with the running total of
     * candles fetched so far, so a caller (BacktestEngine) can report download progress
     * for long historical ranges instead of the UI looking frozen during a 30-90 day pull.
     */
    suspend fun getKlines(
        symbol: String,
        interval: String,
        startTimeMillis: Long,
        endTimeMillis: Long,
        onPageFetched: ((fetchedSoFar: Int) -> Unit)? = null
    ): List<Candle> = withContext(Dispatchers.IO) {
        val result = ArrayList<Candle>()
        var cursor = startTimeMillis
        while (cursor < endTimeMillis) {
            val url = "$BASE_URL?symbol=$symbol&interval=$interval&startTime=$cursor&endTime=$endTimeMillis&limit=$MAX_LIMIT"
            val request = Request.Builder().url(url).get().build()
            val body = execute(request)
            val arr = JSONArray(body)
            if (arr.length() == 0) break
            for (i in 0 until arr.length()) {
                val row = arr.getJSONArray(i)
                val openTime = row.getLong(0)
                val closeTime = row.getLong(6)
                if (closeTime > System.currentTimeMillis()) {
                    // Not actually closed yet - see class doc. Never treat it as CLOSED.
                    continue
                }
                result.add(
                    Candle(
                        openTimeMillis = openTime,
                        open = row.getString(1).toDouble(),
                        high = row.getString(2).toDouble(),
                        low = row.getString(3).toDouble(),
                        close = row.getString(4).toDouble(),
                        volume = row.getString(5).toDouble(),
                        closeTimeMillis = closeTime,
                        isClosed = true
                    )
                )
            }
            onPageFetched?.invoke(result.size)
            val lastOpenTime = arr.getJSONArray(arr.length() - 1).getLong(0)
            if (arr.length() < MAX_LIMIT || lastOpenTime <= cursor) break
            cursor = lastOpenTime + 1
        }
        result
    }

    private fun execute(request: Request): String {
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("Binance REST error: HTTP ${response.code} for ${request.url}")
            }
            return response.body?.string() ?: throw IOException("Empty Binance response body")
        }
    }
}
