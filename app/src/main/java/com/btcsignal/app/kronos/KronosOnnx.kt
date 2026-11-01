package com.btcsignal.app.kronos

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import com.btcsignal.app.data.model.Candle
import java.io.File
import java.nio.FloatBuffer
import java.nio.LongBuffer
import java.time.Instant
import java.time.ZoneOffset
import java.util.Random
import kotlin.math.exp
import kotlin.math.sqrt

/**
 * Kotlin port of Kronos' KronosPredictor.predict + auto_regressive_inference, running the four exported
 * ONNX graphs. Mirrors the original step for step: z-score over the lookback window (+1e-5), clip +-5,
 * half-tokenise (s1/s2), then for every future step: decode_s1 -> sample s1 -> decode_s2 -> sample s2
 * (temperature + nucleus top-p), finally decode tokens back to prices and de-normalise.
 * Unlike the Python helper (which averages samples), every sample path is kept so the caller can vote.
 */
class KronosOnnx(dir: File, val manifest: KronosManifest) : AutoCloseable {
    private val env = OrtEnvironment.getEnvironment()
    private val opts = OrtSession.SessionOptions().apply {
        setIntraOpNumThreads(Runtime.getRuntime().availableProcessors().coerceIn(2, 6))
        setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
    }
    private val encoder = env.createSession(File(dir, "encoder.onnx").path, opts)
    private val s1Net = env.createSession(File(dir, "s1.onnx").path, opts)
    private val s2Net = env.createSession(File(dir, "s2.onnx").path, opts)
    private val decoder = env.createSession(File(dir, "decoder.onnx").path, opts)

    /** @return final predicted CLOSE of the last future step, one value per sample (size = samples). */
    fun forecastFinalCloses(
        history: List<Candle>, predLen: Int, samples: Int,
        temperature: Float = 1.0f, topP: Float = 0.9f, seed: Long = System.nanoTime()
    ): FloatArray {
        val t = history.size
        require(t + predLen <= manifest.maxContext) { "history+pred exceeds model context" }
        val feat = 6
        val x = FloatArray(t * feat)
        for ((i, c) in history.withIndex()) {
            val o = i * feat
            x[o] = c.open.toFloat(); x[o + 1] = c.high.toFloat(); x[o + 2] = c.low.toFloat(); x[o + 3] = c.close.toFloat()
            x[o + 4] = c.volume.toFloat(); x[o + 5] = (c.volume * (c.open + c.high + c.low + c.close) / 4.0).toFloat()
        }
        val mean = FloatArray(feat); val std = FloatArray(feat)
        for (f in 0 until feat) {
            var s = 0.0; for (i in 0 until t) s += x[i * feat + f]
            val m = s / t; var v = 0.0
            for (i in 0 until t) { val d = x[i * feat + f] - m; v += d * d }
            mean[f] = m.toFloat(); std[f] = sqrt(v / t).toFloat()          // np.std (ddof = 0)
        }
        for (i in 0 until t) for (f in 0 until feat) {
            val z = (x[i * feat + f] - mean[f]) / (std[f] + 1e-5f)
            x[i * feat + f] = z.coerceIn(-manifest.clip, manifest.clip)
        }

        // time stamps: minute, hour, weekday(Mon=0), day, month  -- UTC, history then future minutes
        val total = t + predLen
        val stampRow = FloatArray(total * 5)
        val lastOpen = history.last().openTimeMillis
        for (i in 0 until total) {
            val ms = if (i < t) history[i].openTimeMillis else lastOpen + 60_000L * (i - t + 1)
            val z = Instant.ofEpochMilli(ms).atZone(ZoneOffset.UTC)
            val o = i * 5
            stampRow[o] = z.minute.toFloat(); stampRow[o + 1] = z.hour.toFloat(); stampRow[o + 2] = (z.dayOfWeek.value - 1).toFloat()
            stampRow[o + 3] = z.dayOfMonth.toFloat(); stampRow[o + 4] = z.monthValue.toFloat()
        }

        // 1) encode once (deterministic), replicate across the sample batch
        val (pre0, post0) = OnnxTensor.createTensor(env, FloatBuffer.wrap(x), longArrayOf(1, t.toLong(), feat.toLong())).use { xt ->
            encoder.run(mapOf("x" to xt)).use { r ->
                val a = LongArray(t); val b = LongArray(t)
                (r.get(0) as OnnxTensor).longBuffer.get(a); (r.get(1) as OnnxTensor).longBuffer.get(b); a to b
            }
        }
        val b = samples
        val pre = Array(b) { LongArray(total).also { a -> System.arraycopy(pre0, 0, a, 0, t) } }
        val post = Array(b) { LongArray(total).also { a -> System.arraycopy(post0, 0, a, 0, t) } }
        val rng = Random(seed)
        val v1 = 1 shl manifest.s1Bits; val v2 = 1 shl manifest.s2Bits

        // 2) autoregressive sampling
        for (step in 0 until predLen) {
            val cur = t + step
            val preF = LongArray(b * cur); val postF = LongArray(b * cur); val stF = FloatArray(b * cur * 5)
            for (r in 0 until b) {
                System.arraycopy(pre[r], 0, preF, r * cur, cur); System.arraycopy(post[r], 0, postF, r * cur, cur)
                System.arraycopy(stampRow, 0, stF, r * cur * 5, cur * 5)
            }
            val shape2 = longArrayOf(b.toLong(), cur.toLong())
            val s1ids = OnnxTensor.createTensor(env, LongBuffer.wrap(preF), shape2)
            val s2ids = OnnxTensor.createTensor(env, LongBuffer.wrap(postF), shape2)
            val stamp = OnnxTensor.createTensor(env, FloatBuffer.wrap(stF), longArrayOf(b.toLong(), cur.toLong(), 5))
            try {
                s1Net.run(mapOf("s1_ids" to s1ids, "s2_ids" to s2ids, "stamp" to stamp)).use { r1 ->
                    val lg1 = FloatArray(b * v1); (r1.get(0) as OnnxTensor).floatBuffer.get(lg1)
                    val pick1 = LongArray(b) { sample(lg1, it * v1, v1, temperature, topP, rng).toLong() }
                    val s1Pick = OnnxTensor.createTensor(env, LongBuffer.wrap(pick1), longArrayOf(b.toLong(), 1))
                    try {
                        s2Net.run(mapOf("context" to (r1.get(1) as OnnxTensor), "s1_id" to s1Pick)).use { r2 ->
                            val lg2 = FloatArray(b * v2); (r2.get(0) as OnnxTensor).floatBuffer.get(lg2)
                            for (r in 0 until b) {
                                pre[r][cur] = pick1[r]
                                post[r][cur] = sample(lg2, r * v2, v2, temperature, topP, rng).toLong()
                            }
                        }
                    } finally { s1Pick.close() }
                }
            } finally { s1ids.close(); s2ids.close(); stamp.close() }
        }

        // 3) decode all tokens back to (normalised) prices, take last step's close, de-normalise
        val pf = LongArray(b * total); val qf = LongArray(b * total)
        for (r in 0 until b) { System.arraycopy(pre[r], 0, pf, r * total, total); System.arraycopy(post[r], 0, qf, r * total, total) }
        val sh = longArrayOf(b.toLong(), total.toLong())
        val out = FloatArray(b)
        OnnxTensor.createTensor(env, LongBuffer.wrap(pf), sh).use { a ->
            OnnxTensor.createTensor(env, LongBuffer.wrap(qf), sh).use { c ->
                decoder.run(mapOf("s1" to a, "s2" to c)).use { r ->
                    val rec = FloatArray(b * total * feat); (r.get(0) as OnnxTensor).floatBuffer.get(rec)
                    for (i in 0 until b) {
                        val z = rec[(i * total + total - 1) * feat + 3]
                        out[i] = z * (std[3] + 1e-5f) + mean[3]
                    }
                }
            }
        }
        return out
    }

    /** Temperature + nucleus (top-p) sampling, same keep-the-crossing-token rule as Kronos' top_k_top_p_filtering. */
    private fun sample(logits: FloatArray, off: Int, n: Int, temp: Float, topP: Float, rng: Random): Int {
        var mx = Float.NEGATIVE_INFINITY
        for (i in 0 until n) { val v = logits[off + i] / temp; if (v > mx) mx = v }
        val p = DoubleArray(n); var sum = 0.0
        for (i in 0 until n) { p[i] = exp((logits[off + i] / temp - mx).toDouble()); sum += p[i] }
        for (i in 0 until n) p[i] /= sum
        if (topP < 1f) {
            val idx = (0 until n).sortedByDescending { p[it] }
            var cum = 0.0; var cut = n
            for ((rank, id) in idx.withIndex()) { cum += p[id]; if (cum >= topP) { cut = rank + 1; break } }
            val keep = HashSet<Int>(idx.subList(0, cut))
            var s2 = 0.0; for (i in 0 until n) { if (i !in keep) p[i] = 0.0 else s2 += p[i] }
            for (i in 0 until n) p[i] /= s2
        }
        var u = rng.nextDouble(); var last = 0
        for (i in 0 until n) { if (p[i] > 0) { last = i; u -= p[i]; if (u <= 0) return i } }
        return last
    }

    override fun close() { encoder.close(); s1Net.close(); s2Net.close(); decoder.close(); opts.close() }
}
