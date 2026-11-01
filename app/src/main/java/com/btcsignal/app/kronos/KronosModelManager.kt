package com.btcsignal.app.kronos

import android.content.Context
import com.btcsignal.app.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream

sealed class KronosModelState {
    object NotInstalled : KronosModelState()
    data class Downloading(val doneBytes: Long, val totalBytes: Long) : KronosModelState() {
        val fraction: Float get() = if (totalBytes > 0) (doneBytes.toFloat() / totalBytes).coerceIn(0f, 1f) else -1f
    }
    object Verifying : KronosModelState()
    data class Installed(val name: String, val sizeMb: Double) : KronosModelState()
    data class Error(val message: String) : KronosModelState()
}

data class KronosManifest(
    val name: String, val s1Bits: Int, val s2Bits: Int, val dModel: Int,
    val maxContext: Int, val clip: Float, val files: Map<String, String> /* name -> sha256 */
)

/** User-tunable Kronos settings (SharedPreferences, so no DataStore schema change is needed). */
object KronosPrefs {
    private fun p(c: Context) = c.getSharedPreferences("kronos_prefs", Context.MODE_PRIVATE)
    fun modelUrl(c: Context): String = p(c).getString("url", null)?.takeIf { it.isNotBlank() } ?: BuildConfig.KRONOS_MODEL_URL
    fun setModelUrl(c: Context, v: String) = p(c).edit().putString("url", v.trim()).apply()
    /** Minimum share of Kronos' sampled futures that must agree on a colour before a signal is issued. */
    fun threshold(c: Context): Float = p(c).getFloat("threshold", 0.60f).coerceIn(0.50f, 1.0f)
    fun setThreshold(c: Context, v: Float) = p(c).edit().putFloat("threshold", v.coerceIn(0.50f, 1.0f)).apply()
    fun samples(c: Context): Int = p(c).getInt("samples", 16)
    fun lookback(c: Context): Int = p(c).getInt("lookback", 256)
}

object KronosModelManager {
    private val _state = MutableStateFlow<KronosModelState>(KronosModelState.NotInstalled)
    val state: StateFlow<KronosModelState> = _state.asStateFlow()

    @Volatile private var appContext: Context? = null
    @Volatile var engineDirty = true   // set whenever files change, so the engine reloads sessions
        private set

    private val http = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).followRedirects(true).build()

    private fun baseDir(c: Context) = File(c.filesDir, "kronos")
    fun modelDir(c: Context) = File(baseDir(c), "current")

    fun init(c: Context) {
        appContext = c.applicationContext
        refreshInstalledState()
    }

    fun context(): Context? = appContext

    fun loadManifest(dir: File): KronosManifest? = try {
        val j = JSONObject(File(dir, "manifest.json").readText())
        val files = HashMap<String, String>()
        val fo = j.getJSONObject("files")
        for (k in fo.keys()) files[k] = fo.getJSONObject(k).getString("sha256")
        KronosManifest(j.getString("name"), j.getInt("s1_bits"), j.getInt("s2_bits"), j.getInt("d_model"),
            j.getInt("max_context"), j.optDouble("clip", 5.0).toFloat(), files)
    } catch (_: Exception) { null }

    fun isInstalled(): Boolean = _state.value is KronosModelState.Installed

    private fun refreshInstalledState() {
        val c = appContext ?: return
        val dir = modelDir(c)
        val m = loadManifest(dir)
        if (m != null && m.files.keys.all { File(dir, it).exists() }) {
            val mb = m.files.keys.sumOf { File(dir, it).length() } / 1e6
            _state.value = KronosModelState.Installed(m.name, mb)
        } else if (_state.value !is KronosModelState.Downloading) {
            _state.value = KronosModelState.NotInstalled
        }
    }

    fun delete() {
        val c = appContext ?: return
        baseDir(c).deleteRecursively(); engineDirty = true; refreshInstalledState()
    }

    /** Downloads the package, shows byte progress via [state], verifies SHA-256 of every file, then swaps it in atomically. */
    suspend fun download(): Boolean = withContext(Dispatchers.IO) {
        val c = appContext ?: return@withContext false
        if (_state.value is KronosModelState.Downloading) return@withContext false
        val url = KronosPrefs.modelUrl(c)
        if (url.isBlank()) { _state.value = KronosModelState.Error("Model URL is empty. Paste it in the field below."); return@withContext false }
        val tmpZip = File(c.cacheDir, "kronos-onnx.zip")
        val tmpDir = File(baseDir(c), "tmp")
        try {
            _state.value = KronosModelState.Downloading(0, -1)
            http.newCall(Request.Builder().url(url).build()).execute().use { resp ->
                if (!resp.isSuccessful) error("Download failed: HTTP ${resp.code}")
                val body = resp.body ?: error("Empty response")
                val total = body.contentLength()
                var done = 0L; var lastEmit = 0L
                body.byteStream().use { input ->
                    FileOutputStream(tmpZip).use { out ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val n = input.read(buf); if (n < 0) break
                            out.write(buf, 0, n); done += n
                            val now = System.nanoTime()
                            if (now - lastEmit > 100_000_000L) { lastEmit = now; _state.value = KronosModelState.Downloading(done, total) }
                        }
                    }
                }
                _state.value = KronosModelState.Downloading(done, if (total > 0) total else done)
            }
            _state.value = KronosModelState.Verifying
            tmpDir.deleteRecursively(); tmpDir.mkdirs()
            ZipInputStream(tmpZip.inputStream().buffered()).use { zin ->
                while (true) {
                    val e = zin.nextEntry ?: break
                    if (e.isDirectory) continue
                    val out = File(tmpDir, e.name)
                    require(out.canonicalPath.startsWith(tmpDir.canonicalPath + File.separator)) { "Bad zip entry" } // zip-slip guard
                    out.parentFile?.mkdirs()
                    FileOutputStream(out).use { zin.copyTo(it) }
                }
            }
            val m = loadManifest(tmpDir) ?: error("manifest.json missing or invalid")
            for ((name, sha) in m.files) {
                val f = File(tmpDir, name); if (!f.exists()) error("Missing $name")
                if (!sha256(f).equals(sha, ignoreCase = true)) error("Checksum mismatch for $name")
            }
            val cur = modelDir(c)
            cur.deleteRecursively()
            if (!tmpDir.renameTo(cur)) error("Could not install model")
            engineDirty = true
            refreshInstalledState()
            true
        } catch (e: Exception) {
            tmpDir.deleteRecursively()
            _state.value = KronosModelState.Error(e.message ?: "Download failed")
            false
        } finally { tmpZip.delete() }
    }

    fun markEngineLoaded() { engineDirty = false }

    private fun sha256(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { i -> val b = ByteArray(1 shl 20); while (true) { val n = i.read(b); if (n < 0) break; md.update(b, 0, n) } }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}
