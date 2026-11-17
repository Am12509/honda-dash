package app.hondadash

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import android.os.Handler
import android.os.Looper

/**
 * App-wide state shared by the screen and the background service: one engine, the event list, the log.
 * The service keeps the connection alive when the screen is closed.
 */
object Dash {
    private val main = Handler(Looper.getMainLooper())
    val events = ArrayList<String>()
    /** Called on the main thread for every new event (the screen shows toasts and the event list). */
    var listener: ((String) -> Unit)? = null

    val engine = Engine { msg -> main.post { addEvent(msg) } }

    fun prefs(ctx: Context): SharedPreferences = ctx.getSharedPreferences("hondadash", Context.MODE_PRIVATE)

    fun addEvent(s: String) {
        val t = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date())
        events.add("$t  $s")
        while (events.size > 300) events.removeAt(0)
        listener?.invoke(s)
    }

    /** Starts the background service (it connects when the user wants to be connected). */
    fun startService(ctx: Context, action: String? = null) {
        val i = Intent(ctx, DashService::class.java)
        if (action != null) i.action = action
        try {
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
        } catch (e: Exception) {
            addEvent("Background service not started: ${e.javaClass.simpleName} ${e.message}")
        }
    }

    /** True when the engine is connected or trying to connect. */
    fun active(): Boolean = engine.isRunning()

    /** Connects to the saved adapter (or the demo) and keeps reconnecting until [disconnect]. */
    @SuppressLint("MissingPermission")
    fun connect(ctx: Context) {
        val p = prefs(ctx)
        engine.fastMode = p.getBoolean("fast", true)
        val app = ctx.applicationContext
        if (p.getBoolean("demo", false)) {
            engine.start("Demo (no car)", true) { DemoLink() }
            return
        }
        val addr = p.getString("addr", null)
        if (addr == null) { addEvent("Pick the OBD adapter in Settings"); return }
        engine.start(addr, true) {
            val bm = app.getSystemService(BluetoothManager::class.java) ?: throw IllegalStateException("no Bluetooth")
            val ad = bm.adapter ?: throw IllegalStateException("no Bluetooth")
            if (!ad.isEnabled) throw IllegalStateException("Bluetooth is off")
            BluetoothLink(ad.getRemoteDevice(addr))
        }
    }

    fun disconnect(ctx: Context) {
        stopLog(ctx)
        engine.stop()
        addEvent("Disconnected")
    }

    // ------------------------------------------------------------------ logging

    fun startLog(ctx: Context, keys: List<String>, tag: String): CsvLogger {
        val derived = listOf(Derived.TGAP, Derived.SLIP, Derived.GEAR_CALC, Derived.FUEL_FLOW)
        val lg = CsvLogger(ctx.applicationContext, keys + derived, tag)
        engine.logKeys = keys.toSet()
        engine.logger = lg
        addEvent("Logging: ${lg.fileName}")
        return lg
    }

    fun stopLog(ctx: Context) {
        val lg = engine.logger ?: return
        engine.logger = null
        engine.logKeys = emptySet()
        lg.close()
        addEvent("Saved ${lg.fileName} (${lg.rows} rows). Share it from the Logs tab." + (lg.error?.let { " Write error: $it" } ?: ""))
        val f = lg.file
        val app = ctx.applicationContext
        Thread {
            val msg = try { "Copy in " + CsvLogger.exportToDownloads(app, f) } catch (e: Exception) {
                "Downloads copy failed (${e.javaClass.simpleName}: ${e.message}). Use Share on the Logs tab."
            }
            main.post { addEvent(msg) }
        }.start()
    }
}
