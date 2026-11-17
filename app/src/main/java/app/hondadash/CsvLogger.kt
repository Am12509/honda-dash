package app.hondadash

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Writes one CSV row per poll cycle to Download/HondaDash on the head unit.
 * Columns: time, then every logged value by its full name. Values not read yet are empty.
 */
class CsvLogger(context: Context, private val keys: List<String>, tag: String) {
    val fileName: String
    private val out: OutputStreamWriter
    private val t0 = System.currentTimeMillis()
    private val fmt = SimpleDateFormat("dd-MMM-yyyy HH:mm:ss.SSS", Locale.US)
    var rows = 0
        private set

    init {
        val stamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date())
        fileName = "HondaDash_${stamp}_$tag.csv"
        val stream: OutputStream = if (Build.VERSION.SDK_INT >= 29) {
            val cv = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(MediaStore.MediaColumns.MIME_TYPE, "text/csv")
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/HondaDash")
            }
            val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv)
                ?: throw IllegalStateException("Cannot create $fileName")
            context.contentResolver.openOutputStream(uri) ?: throw IllegalStateException("Cannot open $fileName")
        } else {
            val dir = File(context.getExternalFilesDir(null), "logs").apply { mkdirs() }
            FileOutputStream(File(dir, fileName))
        }
        out = OutputStreamWriter(stream, Charsets.UTF_8)
        val header = StringBuilder("Device Time,Seconds")
        for (k in keys) {
            val p = Pids[k]
            val name = p?.let { if (it.unit.isNotEmpty()) "${it.name}(${it.unit})" else it.name } ?: (Derived.names[k] ?: k)
            header.append(',').append(name.replace(',', ' '))
        }
        out.write(header.append('\n').toString())
    }

    @Synchronized
    fun row(values: Map<String, Double>) {
        val now = System.currentTimeMillis()
        val sb = StringBuilder(fmt.format(Date(now))).append(',').append(String.format(Locale.US, "%.3f", (now - t0) / 1000.0))
        for (k in keys) {
            sb.append(',')
            val v = values[k]
            if (v != null && !v.isNaN()) sb.append(String.format(Locale.US, "%.3f", v))
        }
        out.write(sb.append('\n').toString())
        rows++
        if (rows % 20 == 0) out.flush()
    }

    @Synchronized
    fun close() {
        try { out.flush(); out.close() } catch (_: Exception) {}
    }
}
