package com.btcsignal.app.kronos

import com.btcsignal.app.data.model.*
import com.btcsignal.app.engine.DebugTraceEntry
import com.btcsignal.app.engine.EngineResult
import com.btcsignal.app.engine.MarketDataStore
import java.time.Instant
import java.util.UUID

/**
 * The ONLY signal source of the app. At Checkpoint A (+1 min) / B (+2 min) of a 5-minute candle it feeds
 * Kronos every CLOSED 1-minute candle up to now (look-ahead free: the forming minute is never passed),
 * asks it to sample [samples] possible futures for the remaining minutes of the candle, and counts how many
 * of those futures close the 5m candle green vs red (close > candle open = green, otherwise red).
 * A signal is issued only when one colour reaches the confidence threshold; otherwise NO_SIGNAL.
 */
object KronosEngine {
    private var onnx: KronosOnnx? = null
    private const val MIN_HISTORY = 96

    @Synchronized
    private fun session(): KronosOnnx? {
        val ctx = KronosModelManager.context() ?: return null
        if (!KronosModelManager.isInstalled()) return null
        if (KronosModelManager.engineDirty) { onnx?.close(); onnx = null }
        if (onnx == null) {
            val dir = KronosModelManager.modelDir(ctx)
            val m = KronosModelManager.loadManifest(dir) ?: return null
            onnx = KronosOnnx(dir, m)
            KronosModelManager.markEngineLoaded()
        }
        return onnx
    }

    // ---- vote cache (file: filesDir/kronos/vote_cache.csv; wiped together with the model) ----
    private val cache = HashMap<String, FloatArray>()
    private var cacheLoaded = false
    private fun cacheFile(ctx: android.content.Context) = java.io.File(ctx.filesDir, "kronos/vote_cache.csv")

    @Synchronized private fun cacheGet(ctx: android.content.Context, key: String): FloatArray? {
        if (!cacheLoaded) {
            cacheLoaded = true
            try { cacheFile(ctx).takeIf { it.exists() }?.forEachLine { l ->
                val p = l.split(';'); if (p.size == 4) cache[p[0]] = floatArrayOf(p[1].toFloat(), p[2].toFloat(), p[3].toFloat())
            } } catch (_: Exception) {}
        }
        return cache[key]
    }
    @Synchronized private fun cachePut(ctx: android.content.Context, key: String, v: FloatArray) {
        cache[key] = v
        try { val f = cacheFile(ctx); f.parentFile?.mkdirs(); f.appendText("$key;${v[0]};${v[1]};${v[2]}\n") } catch (_: Exception) {}
    }
    fun clearCache(ctx: android.content.Context) { synchronized(this) { cache.clear(); cacheLoaded = true; cacheFile(ctx).delete() } }

    fun evaluate(
        store: MarketDataStore, candleOpenTimeMillis: Long, candleOpen: Double,
        checkpoint: Checkpoint, referencePrice: Double, timestampMillis: Long
    ): EngineResult {
        val candleId = Instant.ofEpochMilli(candleOpenTimeMillis).toString()
        val movePct = if (candleOpen == 0.0) 0.0 else (referencePrice - candleOpen) / candleOpen * 100.0
        fun none(msg: String) = EngineResult(null, DebugTraceEntry(candleId, timestampMillis, checkpoint, referencePrice, movePct,
            null, emptyList(), msg, "NO_SIGNAL: $msg", false))

        val ctx = KronosModelManager.context() ?: return none("Kronos not initialised")
        val net = try { session() } catch (e: Throwable) { return none("Kronos failed to load: ${e.message}") }
            ?: return none("Kronos model not installed - open the Kronos tab and tap Download")

        val closed = store.closed1m()
        val inCandle = closed.count { it.openTimeMillis >= candleOpenTimeMillis && it.openTimeMillis < candleOpenTimeMillis + 300_000L }
        val predLen = 5 - inCandle
        if (inCandle !in 0..4) return none("Candle already complete")
        val lookback = minOf(KronosPrefs.lookback(ctx), net.manifest.maxContext - predLen)
        val hist = closed.filter { it.openTimeMillis < candleOpenTimeMillis + 300_000L }.takeLast(lookback)
        if (hist.size < MIN_HISTORY) return none("Not enough 1m history (${hist.size}/$MIN_HISTORY)")

        val samples = KronosPrefs.samples(ctx)
        // Votes are cached per (model, candle, checkpoint, samples, lookback, open): the threshold is applied AFTER
        // the cache, so you can change it and re-run Backtest without re-running the model.
        val key = "${net.manifest.name}|$candleOpenTimeMillis|$checkpoint|$samples|$lookback|$candleOpen"
        var entry = cacheGet(ctx, key)
        if (entry == null) {
            val finals = try { net.forecastFinalCloses(hist, predLen, samples) } catch (e: Throwable) { return none("Kronos inference error: ${e.message}") }
            entry = floatArrayOf(finals.count { it > candleOpen }.toFloat(), finals.size.toFloat(), finals.sorted()[finals.size / 2])
            cachePut(ctx, key, entry)
        }
        val green = entry[0].toInt(); val nSamples = entry[1].toInt(); val median = entry[2]
        val pGreen = green.toDouble() / nSamples
        val pRed = 1.0 - pGreen                        // tie counts as red (same rule as the result check)
        val th = KronosPrefs.threshold(ctx).toDouble()
        val dir = when { pGreen >= th -> Direction.GREEN; pRed >= th -> Direction.RED; else -> null }
        val summary = "Kronos ${net.manifest.name}: ${green}/${nSamples} futures green (pGreen=${"%.2f".format(pGreen)}, need >= ${"%.2f".format(th)}), median final close ${"%.1f".format(median)}"
        if (dir == null) return EngineResult(null, DebugTraceEntry(candleId, timestampMillis, checkpoint, referencePrice, movePct,
            null, emptyList(), summary, "NO_SIGNAL: $summary", false))

        val p = if (dir == Direction.GREEN) pGreen else pRed
        val signal = Signal(
            signalId = UUID.randomUUID().toString(), candleId = candleId, candleOpenTimeMillis = candleOpenTimeMillis,
            signalTimestampMillis = timestampMillis, candleOpen = candleOpen, signalPrice = referencePrice, direction = dir,
            activeStrategyId = "KRONOS", activeStrategyName = "Kronos (${net.manifest.name})",
            marketRegime = MarketRegimeState(TrendRegime.SIDEWAYS, VolatilityRegime.MEDIUM, MomentumRegime.WEAK), // unused by Kronos
            strategyScore = p, confidencePct = p * 100.0, entryMovePct = movePct, checkpoint = checkpoint
        )
        return EngineResult(signal, DebugTraceEntry(candleId, timestampMillis, checkpoint, referencePrice, movePct,
            null, emptyList(), summary, "SIGNAL_GENERATED: $summary -> $dir", true))
    }
}
