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
 * App-wide state shared by the screen and the background service: one engine, the event list, the log,
 * the trip computer and the graph history. The service keeps the connection alive when the screen is closed.
 */
object Dash {
    private val main = Handler(Looper.getMainLooper())
    val events = ArrayList<String>()
    /** Called on the main thread for every new event (the screen shows toasts and the event list). */
    var listener: ((String) -> Unit)? = null

    val engine = Engine { msg -> main.post { addEvent(msg) } }
    @Volatile var trip: Trip? = null
        private set
    val history = History()

    /**
     * Disconnect pressed: stay disconnected until Connect is pressed, the app is opened again or the unit restarts.
     * Auto connect itself stays on (Settings switch "auto").
     */
    @Volatile var paused = false

    fun prefs(ctx: Context): SharedPreferences = ctx.getSharedPreferences("hondadash", Context.MODE_PRIVATE)

    /** Sets up the trip computer and the per-cycle hook once per process. */
    @Synchronized
    fun init(ctx: Context) {
        if (trip != null) return
        val t = Trip(prefs(ctx.applicationContext))
        trip = t
        engine.onCycle = { now -> onCycle(t, now) }
    }

    private fun fresh(key: String, now: Long, maxAgeMs: Long = 4000): Double? {
        val st = engine.stamps[key] ?: return null
        if (now - st > maxAgeMs) return null
        return engine.values[key]?.takeIf { !it.isNaN() }
    }

    /** Worker thread, after every poll cycle. */
    private fun onCycle(t: Trip, now: Long) {
        t.update(now, fresh("RPM_E", now), fresh("SPEED", now), fresh("FUEL_EVT", now), fresh("DFCO", now))
        val v = engine.values
        if (!t.nowLh.isNaN()) v[Derived.FUEL_LH] = t.nowLh else v.remove(Derived.FUEL_LH)
        val kml = if (!t.nowLh.isNaN() && t.nowSpeed > 3 && t.nowLh > 0.01) t.nowSpeed / t.nowLh else Double.NaN
        if (!kml.isNaN()) v[Derived.KML] = kml else v.remove(Derived.KML)
        history.capture(engine, now)
    }

    fun addEvent(s: String) {
        val t = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date())
        events.add("$t  $s")
        while (events.size > 300) events.removeAt(0)
        listener?.invoke(s)
    }

    /** Starts the background service (it connects when [wants] is true). */
    fun startService(ctx: Context, action: String? = null) {
        val i = Intent(ctx, DashService::class.java)
        if (action != null) i.action = action
        try {
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
        } catch (e: Exception) {
            addEvent("Background service not started: ${e.javaClass.simpleName} ${e.message}")
        }
    }

    /** True when auto connect is on, Disconnect was not pressed and an adapter (or the demo) is chosen. */
    fun wants(ctx: Context): Boolean {
        val p = prefs(ctx)
        return p.getBoolean("auto", true) && !paused && (p.getString("addr", null) != null || p.getBoolean("demo", false))
    }

    /** True when the engine is connected or trying to connect. */
    fun active(): Boolean = engine.isRunning()

    /** Connects to the saved adapter (or the demo) and keeps reconnecting until [disconnect]. */
    @SuppressLint("MissingPermission")
    fun connect(ctx: Context) {
        init(ctx)
        paused = false
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
            val pp = prefs(app)
            BluetoothLink(ad.getRemoteDevice(addr), pp.getInt("sock", 0)) { m -> pp.edit().putInt("sock", m).apply() }
        }
    }

    fun disconnect(ctx: Context) {
        paused = true
        stopLog(ctx)
        engine.stop()
        trip?.save()
        addEvent("Disconnected")
    }

    // ------------------------------------------------------------------ logging

    fun startLog(ctx: Context, keys: List<String>, tag: String): CsvLogger {
        val derived = listOf(Derived.TGAP, Derived.SLIP, Derived.GEAR_CALC, Derived.FUEL_LH, Derived.KML)
        val lg = CsvLogger(ctx.applicationContext, (keys + derived).distinct(), tag)
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

/** Last few minutes of every value, for the Graph screen. One point per new reading. */
class History {
    class Series(cap: Int) {
        val t = LongArray(cap)
        val v = FloatArray(cap)
        var start = 0
        var size = 0
        fun add(time: Long, value: Float) {
            val cap = t.size
            val i = (start + size) % cap
            t[i] = time; v[i] = value
            if (size < cap) size++ else start = (start + 1) % cap
        }
        /** Index [k] counted from the oldest point. */
        fun time(k: Int): Long = t[(start + k) % t.size]
        fun value(k: Int): Float = v[(start + k) % v.size]
    }

    private val series = HashMap<String, Series>()
    private val lastStamp = HashMap<String, Long>()

    @Synchronized
    fun capture(e: Engine, now: Long) {
        for ((k, st) in e.stamps) {
            if (lastStamp[k] == st) continue
            lastStamp[k] = st
            val v = e.values[k] ?: continue
            if (v.isNaN()) continue
            series.getOrPut(k) { Series(CAP) }.add(st, v.toFloat())
        }
        for (k in Derived.graphKeys) {
            val v = e.values[k] ?: continue
            if (v.isNaN()) continue
            series.getOrPut(k) { Series(CAP) }.add(now, v.toFloat())
        }
    }

    /** Copy of the points of [key] newer than [since], as (time, value) arrays. */
    @Synchronized
    fun points(key: String, since: Long): Pair<LongArray, FloatArray> {
        val s = series[key] ?: return LongArray(0) to FloatArray(0)
        var first = 0
        while (first < s.size && s.time(first) < since) first++
        val n = s.size - first
        val ts = LongArray(n); val vs = FloatArray(n)
        for (k in 0 until n) { ts[k] = s.time(first + k); vs[k] = s.value(first + k) }
        return ts to vs
    }

    companion object {
        const val CAP = 900
    }
}
