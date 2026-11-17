package app.hondadash

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Writes one CSV row per poll cycle into the app's own log folder, which needs no permission and always works.
 * Every row is flushed, so a log survives the head unit losing power. When the log stops, a copy goes to
 * Download/HondaDash (best effort). The Logs screen can share any log directly.
 */
class CsvLogger(context: Context, keyList: List<String>, tag: String) {
    /** Every log also records the link rate, so a slow adapter shows up in the file. */
    private val keys: List<String> = (keyList + Derived.RATE).distinct()
    val file: File
    val fileName: String get() = file.name
    private val out: OutputStreamWriter
    private val t0 = System.currentTimeMillis()
    private val fmt = SimpleDateFormat("dd-MMM-yyyy HH:mm:ss.SSS", Locale.US)
    var rows = 0
        private set
    @Volatile var error: String? = null
        private set
    private var closed = false

    init {
        val stamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date())
        file = File(logDir(context), "HondaDash_${stamp}_$tag.csv")
        out = OutputStreamWriter(FileOutputStream(file), Charsets.UTF_8)
        val header = StringBuilder("Device Time,Seconds")
        for (k in keys) {
            val p = Pids[k]
            val name = p?.let { if (it.unit.isNotEmpty()) "${it.name}(${it.unit})" else it.name } ?: (Derived.names[k] ?: k)
            header.append(',').append(name.replace(',', ' '))
        }
        out.write(header.append('\n').toString())
        out.flush()
    }

    /** Never throws: a full disk or a closed file must not stop the polling. */
    @Synchronized
    fun row(values: Map<String, Double>) {
        if (closed) return
        val now = System.currentTimeMillis()
        val sb = StringBuilder(fmt.format(Date(now))).append(',').append(String.format(Locale.US, "%.3f", (now - t0) / 1000.0))
        for (k in keys) {
            sb.append(',')
            val v = values[k]
            if (v != null && !v.isNaN()) sb.append(String.format(Locale.US, "%.3f", v))
        }
        try {
            out.write(sb.append('\n').toString())
            out.flush()
            rows++
        } catch (e: Exception) {
            error = "${e.javaClass.simpleName}: ${e.message}"
            closed = true
        }
    }

    @Synchronized
    fun close() {
        if (closed && error == null) return
        closed = true
        try { out.flush(); out.close() } catch (_: Exception) {}
    }

    companion object {
        /** The app's own log folder. Always writable, no permission needed. */
        fun logDir(context: Context): File {
            val base = context.getExternalFilesDir(null) ?: context.filesDir
            return File(base, "logs").apply { mkdirs() }
        }

        /** All logs, newest first. */
        fun list(context: Context): List<File> =
            (logDir(context).listFiles() ?: emptyArray()).filter { it.name.endsWith(".csv") }.sortedByDescending { it.lastModified() }

        /** Copies a log to Download/HondaDash. Returns the place it went, or throws with the reason. */
        fun exportToDownloads(context: Context, f: File): String {
            if (Build.VERSION.SDK_INT >= 29) {
                val cv = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, f.name)
                    put(MediaStore.MediaColumns.MIME_TYPE, "text/csv")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/HondaDash")
                }
                val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv)
                    ?: throw IllegalStateException("the system refused to create the file in Downloads")
                val os = context.contentResolver.openOutputStream(uri) ?: throw IllegalStateException("cannot open the Downloads file")
                os.use { o -> f.inputStream().use { it.copyTo(o) } }
            } else {
                @Suppress("DEPRECATION")
                val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "HondaDash")
                dir.mkdirs()
                f.copyTo(File(dir, f.name), overwrite = true)
            }
            return "Download/HondaDash/${f.name}"
        }
    }
}
