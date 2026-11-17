package app.hondadash

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import java.io.File
import java.io.FileNotFoundException

/**
 * Lets other apps (WhatsApp, Gmail, Drive, Bluetooth, a file manager) read one log file the user chose to share.
 * Read only. Serves only .csv files from the app's own log folder.
 */
class LogProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    private fun fileFor(uri: Uri): File? {
        val name = uri.lastPathSegment ?: return null
        if (name.contains('/') || name.startsWith(".") || !name.endsWith(".csv")) return null
        val ctx = context ?: return null
        val f = File(CsvLogger.logDir(ctx), name)
        return if (f.isFile) f else null
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? {
        val f = fileFor(uri) ?: return null
        val c = MatrixCursor(arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE))
        c.addRow(arrayOf<Any>(f.name, f.length()))
        return c
    }

    override fun getType(uri: Uri): String = "text/csv"

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        val f = fileFor(uri) ?: throw FileNotFoundException(uri.toString())
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0

    companion object {
        const val AUTHORITY = "app.hondadash.logs"
        fun uriFor(f: File): Uri = Uri.parse("content://$AUTHORITY/" + Uri.encode(f.name))
    }
}
