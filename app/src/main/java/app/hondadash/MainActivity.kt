package app.hondadash

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.bluetooth.BluetoothManager
import android.content.ClipData
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.File

class MainActivity : Activity() {
    private val engine get() = Dash.engine
    private val ui = Handler(Looper.getMainLooper())
    private lateinit var prefs: SharedPreferences
    private lateinit var content: FrameLayout
    private lateinit var header: LinearLayout
    private lateinit var pill: LinearLayout
    private lateinit var pillDot: View
    private lateinit var status: TextView
    private lateinit var rate: TextView
    private lateinit var connectBtn: Button
    private lateinit var logBtn: Button
    private lateinit var shiftBar: ShiftBarView
    private lateinit var tabBar: TabBarView
    private var eventsView: TextView? = null
    private var terminalOut: TextView? = null
    private var resumed = false
    /** Text size factor: a bit bigger on the head unit's large low-density screen, normal on a phone. */
    private var uiScale = 1f

    // bindings of the visible screen
    private val gauges = ArrayList<GaugeView>()
    private val tiles = ArrayList<Pair<TileView, (Double?) -> String>>()
    private val lamps = ArrayList<LampView>()
    private val bars = ArrayList<BarView>()
    private val segments = ArrayList<Pair<SegmentView, () -> Int>>()
    private var chart: ChartGridView? = null
    private var graphInfo: TextView? = null
    private var rpmGauge: GaugeView? = null
    private var speedGauge: GaugeView? = null
    private var coolantGauge: GaugeView? = null
    private var currentPage = 0
    private var lastState: Engine.State? = null
    private var logStart = 0L
    private var loggingShown = false
    private var headerRed = false
    private var lastChart = 0L
    private var tone: ToneGenerator? = null
    private var recStatus: TextView? = null
    /** ECU feature bits the current page was built with (Graph and Tune rebuild when the file's features show up). */
    private var builtFeat = -1

    private val CORE_LOG = listOf("RPM_E", "SPEED", "TPS", "GEAR", "ECT_F", "T_FIN")

    private class Page(val title: String, val short: String, val keys: () -> List<String>, val fast: Set<String> = emptySet(),
                       val build: (LinearLayout) -> Unit)
    private lateinit var pages: List<Page>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Dash.prefs(this)
        Theme.set(prefs.getString("theme", "papaya"))
        uiScale = if (resources.configuration.smallestScreenWidthDp >= 600) 1.2f else 1f
        Dash.init(this)
        try { tone = ToneGenerator(AudioManager.STREAM_MUSIC, 70) } catch (_: Exception) {}
        Dash.paused = false          // opening the app always reconnects (unless auto connect is switched off)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        pages = buildPages()
        setContentView(buildRoot())
        showPage(prefs.getInt("page", 0).coerceIn(0, pages.size - 1))
        Dash.listener = { s -> onEvent(s) }
        val need = ArrayList<String>()
        if (Build.VERSION.SDK_INT >= 31 && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED)
            need.add(Manifest.permission.BLUETOOTH_CONNECT)
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            need.add(Manifest.permission.POST_NOTIFICATIONS)
        if (need.isNotEmpty()) requestPermissions(need.toTypedArray(), 1) else startAuto()
        ui.post(fastTick)
        ui.post(slowTick)
    }

    /** Starts the service and, when auto connect is wanted, the connection right away. */
    private fun startAuto() {
        Dash.startService(this)
        if (Dash.wants(this) && !Dash.active()) Dash.connect(this)
        else if (engine.state == Engine.State.WAITING) engine.retryNow()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        startAuto()
    }

    @Suppress("DEPRECATION")
    override fun onResume() {
        super.onResume()
        resumed = true
        window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_LAYOUT_STABLE)
        // the head unit woke up: make sure the service runs and retries at once
        if (Dash.wants(this)) startAuto()
    }

    override fun onPause() {
        resumed = false
        Dash.trip?.save()
        super.onPause()
    }

    override fun onDestroy() {
        ui.removeCallbacks(fastTick)
        ui.removeCallbacks(slowTick)
        Dash.listener = null
        tone?.release()
        super.onDestroy()
    }

    // ------------------------------------------------------------------ sounds

    private fun soundsOn(): Boolean = prefs.getBoolean("snd_buttons", true)
    /** Short click on every press. */
    private fun click() { if (soundsOn()) try { tone?.startTone(ToneGenerator.TONE_PROP_BEEP, 40) } catch (_: Exception) {} }
    /** The ECU confirmed a change (two rising beeps) or refused it (low beep). */
    private fun confirmSound(ok: Boolean) {
        if (soundsOn()) try { tone?.startTone(if (ok) ToneGenerator.TONE_PROP_ACK else ToneGenerator.TONE_PROP_NACK, if (ok) 220 else 300) } catch (_: Exception) {}
    }

    // ------------------------------------------------------------------ layout

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
    private fun dpf(v: Float): Float = v * resources.displayMetrics.density

    private fun styled(b: Button, size: Float, color: Int, bg: GradientDrawable) {
        b.isAllCaps = false
        b.textSize = size
        b.typeface = Theme.TECH
        b.setTextColor(color)
        b.background = bg
        b.minHeight = 0; b.minimumHeight = 0; b.minWidth = 0; b.minimumWidth = 0
        b.stateListAnimator = null
        b.setPadding(dp(12), 0, dp(12), 0)
    }

    /** Raised key look for plain action buttons (dark gradient, light edge). */
    private fun keyBg(): GradientDrawable = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
        intArrayOf(Color.rgb(52, 58, 74), Color.rgb(26, 30, 41))).apply {
        cornerRadius = dpf(12f)
        setStroke(1, Color.argb(48, 255, 255, 255))
    }

    private fun buildRoot(): View {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val a = Theme.ACCENT
        root.background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(
            mix(Color.rgb(9, 11, 16), a, 0.10f), Color.rgb(9, 11, 16), Color.rgb(5, 6, 8)))

        shiftBar = ShiftBarView(this)
        root.addView(shiftBar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(14)))

        header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(4), dp(8), dp(4))
            background = Theme.fill(Theme.withAlpha(Theme.BAR, 235), dpf(14f), Color.argb(26, 255, 255, 255))
        }
        pill = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(12), 0, dp(14), 0) }
        pillDot = View(this)
        status = TextView(this).apply { typeface = Theme.TECH; textSize = 15f; setPadding(dp(8), 0, 0, 0); isSingleLine = true }
        pill.addView(pillDot, LinearLayout.LayoutParams(dp(10), dp(10)))
        pill.addView(status)
        rate = TextView(this).apply { setTextColor(Theme.DIM2); typeface = Theme.MONO; textSize = 12f; setPadding(dp(12), 0, dp(8), 0); isSingleLine = true }
        header.addView(pill, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT))
        header.addView(rate, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val dots = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(6), 0, dp(6), 0)
            background = Theme.fill(Color.argb(128, 0, 0, 0), dpf(12f), Color.argb(26, 255, 255, 255))
        }
        for (pal in Theme.palettes) {
            val d = View(this)
            d.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(pal.accent)
                if (pal.id == Theme.current.id) setStroke(dp(2), Color.WHITE)
            }
            d.setOnClickListener { click(); applyTheme(pal.id) }
            dots.addView(d, LinearLayout.LayoutParams(dp(20), dp(20)).apply { setMargins(dp(4), 0, dp(4), 0) })
        }
        header.addView(dots, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT).apply { setMargins(0, dp(2), dp(8), dp(2)) })
        logBtn = Button(this).apply { text = "Start log"; setOnClickListener { click(); if (engine.logger == null) startLog() else stopLog() } }
        styled(logBtn, 15f, Theme.TEXT, keyBg())
        connectBtn = Button(this).apply { text = "Connect"; setOnClickListener { click(); onConnectPressed() } }
        styled(connectBtn, 15f, onColor(Theme.ACCENT), Theme.fill(Theme.ACCENT, dpf(12f)))
        header.addView(logBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT).apply { setMargins(0, 0, dp(8), 0) })
        header.addView(connectBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT))
        root.addView(header, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)).apply { setMargins(dp(6), dp(4), dp(6), 0) })

        content = FrameLayout(this)
        root.addView(content, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        tabBar = TabBarView(this, pages.map { it.title }, pages.map { it.short }, 15f * uiScale) { i -> click(); showPage(i) }
        root.addView(tabBar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(if (uiScale > 1f) 62 else 56)).apply { setMargins(dp(6), 0, dp(6), dp(6)) })
        return root
    }

    private fun applyTheme(id: String) {
        prefs.edit().putString("theme", id).apply()
        Theme.set(id)
        setContentView(buildRoot())
        showPage(currentPage)
        lastState = null
        loggingShown = false
        headerRed = false
    }

    private fun showPage(i: Int) {
        currentPage = i
        prefs.edit().putInt("page", i).apply()
        gauges.clear(); tiles.clear(); lamps.clear(); bars.clear(); segments.clear()
        rpmGauge = null; speedGauge = null; coolantGauge = null; chart = null; graphInfo = null; recStatus = null
        eventsView = null; terminalOut = null
        content.removeAllViews()
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(4), dp(4), dp(4), dp(4)) }
        pages[i].build(body)
        content.addView(body, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        tabBar.selected = i
        builtFeat = engine.ecuFeat
        engine.fast = pages[i].fast
        engine.wanted = pages[i].keys().toSet()
    }

    /** A horizontal row with equal-width cells. [weight] is the row's share of the page height. */
    private fun row(parent: LinearLayout, weight: Float, vararg views: View) {
        val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        for (v in views) r.addView(v, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply { setMargins(dp(3), dp(3), dp(3), dp(3)) })
        parent.addView(r, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, weight))
    }

    /** A row with a fixed height (for scrolling pages). */
    private fun rowFixed(parent: LinearLayout, heightDp: Int, vararg views: View) {
        val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        for (v in views) r.addView(v, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply { setMargins(dp(3), dp(3), dp(3), dp(3)) })
        parent.addView(r, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(heightDp)))
    }

    /** Scrolling column inside the page, for pages taller than a phone screen. */
    private fun scrollColumn(b: LinearLayout, weight: Float = 1f): LinearLayout {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        b.addView(ScrollView(this).apply { addView(col) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, weight))
        return col
    }

    private fun gauge(key: String, title: String, unit: String, min: Double, max: Double, major: Double, minor: Int,
                      red: Double? = null, label: (Double) -> String = { Math.round(it).toString() }): GaugeView =
        GaugeView(this, key, title, unit, min, max, major, minor, red, 0, label).also { gauges.add(it) }

    private fun tile(key: String, label: String? = null, above: Double? = null, below: Double? = null, tag: String = "",
                     style: Int = TileView.NUMBER, barMax: Double? = null, unit: String? = null,
                     text: ((Double?) -> String)? = null): TileView {
        val p = Pids[key]
        val u = unit ?: Derived.units[key] ?: p?.unit ?: ""
        val t = TileView(this, key, label ?: p?.short ?: (Derived.names[key] ?: key), u, above, below, tag, style, barMax)
        val f: (Double?) -> String = text ?: { x ->
            if (p != null) p.format(x) else if (x == null || x.isNaN()) "--" else String.format("%.1f", x)
        }
        tiles.add(t to f)
        return t
    }

    private fun lamp(key: String, label: String? = null): LampView =
        LampView(this, key, label ?: Pids[key]?.short ?: key).also { lamps.add(it) }

    private fun bar(key: String, label: String? = null): BarView =
        BarView(this, key, label ?: Pids[key]?.short ?: key, -30.0, 30.0).also { bars.add(it) }

    /** Row of real buttons; [state] returns the option the ECU reports, [codes] are the mode 23 commands. */
    private fun segment(title: String, options: List<String>, codes: List<String>, state: () -> Int): SegmentView {
        val sv = SegmentView(this, title, options) { i -> click(); sendCommand(title + ": " + options[i], codes[i]) }
        segments.add(sv to state)
        return sv
    }

    private fun actionButton(label: String, onClick: () -> Unit): Button = Button(this).apply {
        text = label
        styled(this, 16f, Theme.TEXT, keyBg())
        setOnClickListener { click(); onClick() }
    }

    private fun note(text: String): TextView = TextView(this).apply {
        this.text = text
        setTextColor(Theme.DIM)
        textSize = 14f
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(6), 0, dp(6), 0)
    }

    private fun panel(child: View): ScrollView = ScrollView(this).apply {
        background = Theme.card(dpf(14f))
        setPadding(dp(4), dp(4), dp(4), dp(4))
        addView(child)
    }

    // ------------------------------------------------------------------ values shown as words

    private fun v(key: String): Double? = engine.values[key]?.takeIf { !it.isNaN() }
    private val modeText: (Double?) -> String = { x -> when (x?.toInt()) { 1 -> "Eco"; 2 -> "Sport"; null -> "--"; else -> "Normal" } }
    private val popText: (Double?) -> String = { x -> when (x?.toInt()) { 1 -> "Mild"; 2 -> "Medium"; 3 -> "Full"; null -> "--"; else -> "Off" } }
    private val onOffText: (Double?) -> String = { x -> if (x == null) "--" else if (x >= 0.5) "On" else "Off" }
    private val luText: (Double?) -> String = { x -> Derived.lockupText(x) }
    private val gearText: (Double?) -> String = { x -> when (x?.toInt()) { null -> "--"; 0 -> "P/N"; 6 -> "R"; else -> x.toInt().toString() } }

    // fuel economy in the chosen unit (km/L by default, or L/100 km)
    private fun l100(): Boolean = prefs.getString("units", "kml") == "l100"
    private fun econUnit(): String = if (l100()) "L/100km" else "km/L"
    private fun econ(km: Double, l: Double): String {
        val kml = Trip.kml(km, l)
        if (kml.isNaN()) return "--"
        return if (l100()) String.format("%.1f", 100.0 / kml) else String.format("%.1f", kml)
    }
    private fun nowText(): String {
        val t = Dash.trip ?: return "--"
        if (t.nowLh.isNaN()) return "--"
        if (t.nowSpeed < 3) return String.format("%.1f L/h", t.nowLh)
        if (t.nowLh < 0.05) return "cut"
        val kml = t.nowSpeed / t.nowLh
        return if (l100()) String.format("%.1f", 100.0 / kml) else String.format("%.1f", Math.min(kml, 99.9))
    }
    private fun calTag(): String = if (Dash.trip?.calibrated == true) "CAL" else "EST"

    private fun fuelTiles(): Array<View> = arrayOf(
        tile("TRIP_NOW", "Fuel now", tag = calTag(), unit = econUnit(), text = { nowText() }),
        tile("TRIP_DRIVE", "This drive avg", tag = calTag(), unit = econUnit(), text = { Dash.trip?.let { econ(it.driveKm, it.driveL) } ?: "--" }),
        tile("TRIP_AVG", "Trip avg", tag = calTag(), unit = econUnit(), text = { Dash.trip?.let { econ(it.tripKm, it.tripL) } ?: "--" }),
        tile("TRIP_KM", "Trip", tag = "KM", unit = "km", text = { Dash.trip?.let { String.format("%.1f", it.tripKm) } ?: "--" }))

    // ------------------------------------------------------------------ screens

    private val timingSets = listOf(
        listOf("T_D6FA", "T_D6FC", "T_D6FE", "T_D700", "T_D702", "T_D704", "T_D706", "T_D708", "T_D778", "T_D77A"),
        listOf("T_D704", "T_D716", "T_D718", "T_D722", "T_D724", "T_D726", "T_D728", "T_D6B4", "T_D70A", "T_D70C",
            "T_D70E", "T_D710", "T_D6EC", "T_B6BE", "T_B6CA", "T_B6A4", "T_C604"))

    private fun timingSet(): Int = prefs.getInt("timing_set", 0).coerceIn(0, 1)

    private fun buildPages(): List<Page> = listOf(
        Page("Drive", "Drive", { listOf("RPM_E", "SPEED", "ECT_F", "GEAR", "MODE", "POP_LVL", "FAN", "VTEC") },
            setOf("RPM_E", "SPEED", "VTEC")) { b ->
            val r = gauge("RPM_E", "Tachometer x1000", "RPM", 0.0, 8000.0, 1000.0, 5, 6500.0) { (Math.round(it) / 1000).toString() }
            val s = gauge("SPEED", "Speedometer", "KM/H", 0.0, 240.0, 20.0, 4)
            val c = gauge("ECT_F", "Coolant temp", "COOLANT F", 100.0, 260.0, 20.0, 4, 225.0)
            rpmGauge = r; speedGauge = s; coolantGauge = c
            // short screens (phone in landscape): smaller dials, taller cards
            row(b, if (resources.configuration.screenHeightDp < 500) 2.2f else 2.6f, r, s, c)
            row(b, 1f, tile("GEAR", "Gear", tag = "5AT", style = TileView.ACCENT, text = gearText),
                tile("MODE", "Mode", tag = "ECU", style = TileView.WORD, text = modeText),
                tile("POP_LVL", "Pops", tag = "LEVEL", style = TileView.WORD, text = popText),
                tile("FAN", "Fan", tag = "COOLING", style = TileView.WORD, unit = "", text = onOffText),
                tile("VTEC", "VTEC", tag = "CAM", style = TileView.WORD, unit = "", text = onOffText))
            row(b, 1f, *fuelTiles())
        },
        Page("Engine", "Engine", { listOf("T_MAP", "T_FIN", "TIMING", "STFT", "LTFT", "LTFT_E", "MAP", "LOAD", "ECT_F", "IAT", "LAMBDA", "LAMBDA2", "IDLE_F", "VTEC", "LOOP", "FAN", "AC_CLUTCH", "RPM_E") }) { b ->
            row(b, 1f, tile("RPM_E"), tile("T_MAP", "Map timing"), tile("T_FIN", "Final timing"), tile(Derived.TGAP, "Removed"), tile("TIMING", "OBD timing"))
            row(b, 1f, tile("STFT"), tile("LTFT", above = 15.0), tile("LTFT_E", "LTFT ECU"), lambdaTile(), tile("MAP"))
            row(b, 1f, tile("LOAD"), tile("ECT_F", above = 225.0), tile("IAT"), tile("IDLE_F", "Idle target"))
            row(b, 0.8f, lamp("VTEC"), lamp("LOOP", "Closed loop"), lamp("FAN"), lamp("AC_CLUTCH", "A/C clutch"))
        },
        Page("Pops & modes", "Pops", { listOf("POP_LVL", "POP_TIMSET", "POP_WINSET", "POP_SIG", "POP_TGT", "POP_CNT", "POP_REQ", "POP_FUEL", "POP_ARM", "MODE", "SMODE", "STALL", "BTN_SHIFT", "FAN", "FAN_OVR", "RPM_E", "T_FIN", "GEAR") }) { b ->
            val col = scrollColumn(b)
            rowFixed(col, 92, tile("POP_TGT", "Pop timing now"), tile("POP_CNT", "Pop count"), tile("T_FIN", "Final timing"),
                tile("GEAR", "Gear", style = TileView.ACCENT, text = gearText))
            rowFixed(col, 66, segment("Pops", listOf("Off", "Mild", "Medium", "Full"), listOf("230700", "230701", "230702", "230703")) {
                when (v("POP_LVL")?.toInt()) { 1 -> 1; 2 -> 2; 3 -> 3; null -> -1; else -> 0 } })
            rowFixed(col, 66, segment("Drive mode", listOf("Eco", "Normal", "Sport"), listOf("230301", "230300", "230302")) {
                when (v("MODE")?.toInt()) { 1 -> 0; 2 -> 2; null -> -1; else -> 1 } })
            rowFixed(col, 66, segment("Pop signal", listOf("Speedo", "Tach", "Off"), listOf("230A00", "230A02", "230A03")) {
                when (v("POP_SIG")?.toInt()) { 2 -> 1; 3 -> 2; null -> -1; else -> 0 } })
            rowFixed(col, 66, segment("Fans", listOf("Auto", "Forced on"), listOf("230100", "230101")) {
                v("FAN_OVR")?.let { if (it >= 0.5) 1 else 0 } ?: -1 })
            rowFixed(col, 66, segment("Cruise button shifting", listOf("Off", "On"), listOf("230200", "230201")) {
                v("BTN_SHIFT")?.let { if (it >= 0.5) 1 else 0 } ?: -1 })
            rowFixed(col, 80, stepper("Pop timing", "POP_TIMSET", "2308", 0, 20, 1, "deg"), stepper("Pop window", "POP_WINSET", "2309", 0, 40, 5, "x0.1 s"))
            rowFixed(col, 84, lamp("POP_REQ", "Cut req"), lamp("POP_FUEL", "Fuel kept"), lamp("POP_ARM", "Armed"), lamp("SMODE", "S mode"),
                lamp("STALL", "Stall shift"), lamp("FAN", "Fan running"))
        },
        Page("Gearbox", "Gear", { listOf("GB_RPM", "MAINSHAFT", "COUNTERSHAFT", "GEAR", "LU_STATE", "TBYTE", "TPS", "SPEED", "SMODE", "X_SHIFT_SCHEDULE_ROW") }) { b ->
            row(b, 1.3f, tile("GB_RPM", "Engine rpm"), tile("MAINSHAFT", "Mainshaft"), tile(Derived.SLIP, "Converter slip %", above = 30.0),
                tile("LU_STATE", "Lock-up", style = TileView.WORD, text = luText))
            row(b, 1f, tile("GEAR", "Gear (ECU)", style = TileView.ACCENT, text = gearText),
                tile(Derived.GEAR_CALC, "Gear (shafts)", style = TileView.ACCENT, text = { x -> if (x == null || x < 1) "--" else x.toInt().toString() }),
                tile("COUNTERSHAFT", "Countershaft"), tile("SPEED"))
            row(b, 1f, tile("TPS"), tile("TBYTE", "Throttle byte"), tile("X_SHIFT_SCHEDULE_ROW", "Shift row"), lamp("SMODE", "S mode"))
            row(b, 0.5f, note("Lock-up meanings are inferred from logs: Partial lock = light-throttle slip control, Coast lock = locked while lifting off."))
        },
        Page("Timing", "Timing", { listOf("T_MAP", "T_FIN", "RPM_E", "TPS") + timingSets[timingSet()] }) { b ->
            val set = timingSet()
            row(b, 1f, tile("T_MAP", "Map timing"), tile("T_FIN", "Final timing"), tile(Derived.TGAP, "Removed", above = 8.0), tile("RPM_E"), tile("TPS"))
            val pick = SegmentView(this, "Values", listOf("Corrections", "Limit path")) { i ->
                click(); prefs.edit().putInt("timing_set", i).apply(); showPage(currentPage) }
            pick.selected = set
            row(b, 0.6f, pick)
            val col = scrollColumn(b, 3f)
            val keys = timingSets[set]
            var k = 0
            while (k < keys.size) {
                val a = bar(keys[k], Pids[keys[k]]?.short)
                val c = if (k + 1 < keys.size) bar(keys[k + 1], Pids[keys[k + 1]]?.short) else View(this)
                rowFixed(col, 54, a, c)
                k += 2
            }
            col.addView(note(if (set == 0) "Final = base (D6FA) + correction (D6FE). D6FE is almost all D704 at full throttle."
                else "D704 is built from D716, D722 and B6BE. Log two 3rd-gear pulls here so the cause of the -13 shows."),
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)))
        },
        Page("Fuel", "Fuel", { listOf("FUEL_BASE", "FUEL_CORR", "FUEL_EVT", "STFT", "LTFT", "LAMBDA", "LAMBDA2", "MAP", "RPM_E", "LOAD", "LOOP", "DFCO") }) { b ->
            row(b, 1f, *fuelTiles(), tile("TRIP_L", "Trip fuel", tag = calTag(), unit = "L", text = { Dash.trip?.let { String.format("%.2f", it.tripL) } ?: "--" }))
            row(b, 0.75f, actionButton("Reset trip") { confirm("Reset the trip?", "Trip km and trip fuel go back to 0.") { Dash.trip?.resetTrip() } },
                actionButton("Full tank now") { confirm("Tank filled to the top?", "Starts counting this tank. At the next fill-up to the top, press Fill-up and enter the litres.") { Dash.trip?.fullTank() } },
                actionButton("Fill-up: enter litres") { fillUpDialog() },
                actionButton(if (l100()) "Units: L/100 km" else "Units: km/L") {
                    prefs.edit().putString("units", if (l100()) "kml" else "l100").apply(); showPage(currentPage) })
            row(b, 1f, tile("STFT"), tile("LTFT", above = 15.0), lambdaTile(), tile("MAP"), tile("LOAD"))
            row(b, 1f, tile("FUEL_BASE", "Base map"), tile("FUEL_CORR", "Corrected"), tile("FUEL_EVT", "Per injection"),
                tile(Derived.FUEL_LH, "Fuel flow", tag = calTag()), lamp("DFCO", "Fuel cut"))
            val t = Dash.trip
            row(b, 0.5f, note(if (t?.calibrated == true) "Fuel figures are calibrated from your fill-ups."
                else "Fuel figures are estimates (EST). For exact figures: fill the tank to the top, press Full tank now, drive at least 50 km, fill to the top again and enter the litres." +
                    (t?.let { String.format("  This tank so far: %.0f km.", it.tankKm) } ?: "")))
        },
        Page("Wheels", "Wheels", { listOf("WHEEL1", "WHEEL2", "WHEEL3", "WHEEL4", "SPEED", "CR_MAIN", "CR_RES", "CR_SET") }) { b ->
            row(b, 1f, tile("WHEEL1", "Wheel 1"), tile("WHEEL2", "Wheel 2"), tile("SPEED", "OBD speed"))
            row(b, 1f, tile("WHEEL3", "Wheel 3"), tile("WHEEL4", "Wheel 4"), lamp("CR_MAIN", "Cruise MAIN"))
            row(b, 0.6f, lamp("CR_RES", "RES"), lamp("CR_SET", "SET"),
                note("Driving straight, all four wheel speeds should match within 1-2 km/h. An odd one points at the VSA sensor fault."))
        },
        Page("Graph", "Graph", { graphSelection().filter { Pids[it] != null } + listOf("ECU_PROTO", "REC_COUNT") }) { b -> buildGraph(b) },
        Page("Tune", "Tune", { Proto.tunables.map { it.key } + listOf("ECU_PROTO") }) { b -> buildTune(b) },
        Page("Diag", "Diag", { emptyList() }) { b -> buildDiag(b) },
        Page("Logs", "Logs", { emptyList() }) { b -> buildLogs(b) },
        Page("Settings", "Setup", { emptyList() }) { b -> buildSettings(b) },
    )

    /** A/F sensor: shows whichever of the two lambda requests the ECU answers. */
    private fun lambdaTile(): TileView = tile("LAMBDA_ANY", "A/F lambda", unit = "", text = {
        val l = v("LAMBDA") ?: v("LAMBDA2")
        if (l == null) "--" else String.format("%.2f", l)
    })

    /** - / + control for a mode 23 setting. 0 means "use the level default". */
    private fun stepper(label: String, key: String, prefix: String, min: Int, max: Int, firstNonZero: Int, unit: String): View {
        val box = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val t = tile(key, label, text = { x -> if (x == null) "--" else if (x.toInt() == 0) "auto" else x.toInt().toString() + " " + unit })
        val minus = actionButton("-") {
            val cur = engine.values[key]?.toInt() ?: 0
            val next = if (cur <= firstNonZero) 0 else cur - 1
            sendCommand(label, prefix + String.format("%02X", next.coerceIn(min, max)))
        }
        val plus = actionButton("+") {
            val cur = engine.values[key]?.toInt() ?: 0
            val next = if (cur == 0) firstNonZero else cur + 1
            sendCommand(label, prefix + String.format("%02X", next.coerceIn(min, max)))
        }
        box.addView(minus, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        box.addView(t, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 2f).apply { setMargins(dp(4), 0, dp(4), 0) })
        box.addView(plus, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        return box
    }

    // ------------------------------------------------------------------ graph

    private fun allGraphKeys(): List<String> = PidTable.all.map { it.key } + Derived.graphKeys

    /** Selected values. By default every value is selected. */
    private fun graphSelection(): List<String> {
        val s = prefs.getString("graph_sel", "ALL") ?: "ALL"
        if (s == "ALL") return allGraphKeys()
        if (s.isEmpty()) return emptyList()
        val chosen = s.split(',').toSet()
        return allGraphKeys().filter { it in chosen }
    }

    private fun categoryOf(k: String): String = Pids[k]?.category ?: "Calculated"

    private fun categories(): List<String> = listOf("Engine", "Temperatures", "Fuel", "Timing", "Gearbox", "Modes, pops, fans", "Wheels and cruise", "Calculated")

    private fun buildGraph(b: LinearLayout) {
        val sel = graphSelection()
        val window = prefs.getLong("graph_win", 120_000L)
        val info = note("")
        graphInfo = info
        val rec = actionButton(if (engine.logger == null) "Record selected" else "Stop recording") {
            if (engine.logger == null) {
                if (engine.state != Engine.State.CONNECTED) { toast("Connect first"); return@actionButton }
                Dash.startLog(this, sel.filter { Pids[it] != null }, "Graph")
            } else Dash.stopLog(this)
            showPage(currentPage)
        }
        row(b, 0.55f, actionButton("Select values (${sel.size})") { selectDialog() }, rec,
            actionButton(if (window <= 60_000L) "Window: 1 min" else if (window <= 120_000L) "Window: 2 min" else "Window: 5 min") {
                val next = when { window <= 60_000L -> 120_000L; window <= 120_000L -> 300_000L; else -> 60_000L }
                prefs.edit().putLong("graph_win", next).apply(); showPage(currentPage) },
            info)
        // 10 ms recorder (ECU file v5.3 and newer). Older files: one short line instead of dead buttons.
        val recOk = engine.ecuFeat and Proto.F_RECORDER != 0
        if (!recOk) {
            b.addView(note("10 ms recorder: needs ECU file v5.3 or newer" + fileText() + ". The buttons appear once that file is flashed."),
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(40)))
        } else {
            val recInfo = note("10 ms recorder: ready")
            recStatus = recInfo
            val arm = { mode: Int, what: String ->
                if (engine.state != Engine.State.CONNECTED) toast("Connect first")
                else engine.recorderArm(mode) { ok -> ui.post { confirmSound(ok); toast(if (ok) what else "Recorder: no reply from ECU") } }
            }
            row(b, 0.5f, recInfo,
                actionButton("Record now") { arm(1, "Recording 10 ms data now") },
                actionButton("Arm: full throttle") { arm(2, "Armed: records when you go full throttle") },
                actionButton("Arm: lift-off") { arm(3, "Armed: records when you lift off") },
                actionButton("Download") {
                    if (engine.state != Engine.State.CONNECTED) return@actionButton toast("Connect first")
                    toast("Downloading the recording...")
                    engine.recorderDownload(CsvLogger.logDir(this)) { f, msg ->
                        ui.post {
                            confirmSound(f != null)
                            if (f != null) Dash.addEvent("Recorder saved ${f.name} ($msg). Share it from the Logs tab.")
                            toast(if (f != null) "Saved: $msg. See the Logs tab." else "Recorder: $msg")
                        }
                    }
                },
                actionButton("Stop") { engine.recorderStop(); confirmSound(true); toast("Recorder stopped and cleared") })
        }
        val grid = ChartGridView(this)
        grid.windowMs = window
        grid.name = { k -> Pids[k]?.name ?: (Derived.names[k]?.substringBefore('(') ?: k) }
        grid.unit = { k -> Pids[k]?.unit ?: Derived.units[k] ?: "" }
        grid.format = { k, x -> val p = Pids[k]; if (p != null) p.format(x) else String.format("%.1f", x) }
        grid.source = { k, since -> Dash.history.points(k, since) }
        // charts grouped by category, in the picker's order
        grid.keys = sel.sortedBy { categories().indexOf(categoryOf(it)) }
        chart = grid
        b.addView(ScrollView(this).apply { addView(grid) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 4f))
    }

    private fun selectDialog() {
        val chosen = graphSelection().toMutableSet()
        val all = allGraphKeys()
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(8), dp(12), dp(8)) }
        val boxes = HashMap<String, CheckBox>()
        for (cat in categories()) {
            val keys = all.filter { categoryOf(it) == cat }
            if (keys.isEmpty()) continue
            val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            head.addView(TextView(this).apply { text = "$cat (${keys.size})"; textSize = 17f; typeface = Theme.TECH; setTextColor(Theme.ACCENT) },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            head.addView(actionButton("All") { keys.forEach { boxes[it]?.isChecked = true } }, LinearLayout.LayoutParams(dp(80), dp(40)).apply { setMargins(dp(4), dp(6), dp(4), dp(6)) })
            head.addView(actionButton("None") { keys.forEach { boxes[it]?.isChecked = false } }, LinearLayout.LayoutParams(dp(80), dp(40)).apply { setMargins(dp(4), dp(6), dp(4), dp(6)) })
            col.addView(head)
            for (k in keys) {
                val cb = CheckBox(this).apply {
                    text = Pids[k]?.name ?: Derived.names[k] ?: k
                    isChecked = k in chosen
                    setTextColor(Theme.TEXT)
                    textSize = 15f
                }
                boxes[k] = cb
                col.addView(cb)
            }
        }
        AlertDialog.Builder(this).setTitle("Values to show and record")
            .setView(ScrollView(this).apply { addView(col) })
            .setPositiveButton("Done") { _, _ ->
                val sel = all.filter { boxes[it]?.isChecked == true }
                prefs.edit().putString("graph_sel", if (sel.size == all.size) "ALL" else sel.joinToString(",")).apply()
                showPage(currentPage)
            }
            .setNegativeButton("Cancel", null).show()
    }

    private fun fileText(): String = if (engine.state != Engine.State.CONNECTED) "" else
        if (engine.ecuProto in 0x53..0xEF) String.format(" (your file: protocol %02X)", engine.ecuProto) else " (your file: v5.2 or older)"

    // ------------------------------------------------------------------ live tuning

    private fun buildTune(b: LinearLayout) {
        val ok = engine.ecuFeat and Proto.F_TUNING != 0
        b.addView(note(if (ok) "Live tuning: changes apply at once and last until the ignition is off. Stock = the value in the file. Rev limits are locked. Raise timing one step at a time with a log running."
            else "Live tuning needs ECU file v5.3 or newer" + fileText() + ". The controls switch on by themselves once that file is flashed."),
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56)))
        val col = scrollColumn(b)
        for (t in Proto.tunables) {
            val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            val valueTile = tile(t.key, t.name, unit = t.unit, text = { x -> if (!ok || x == null) "--" else if (x == 0.0) "stock" else Proto.fmt(x) })
            val minus = actionButton("-") { stepTune(t, -t.step) }
            val plus = actionButton("+") { stepTune(t, t.step) }
            val set = actionButton("Set") { setTuneDialog(t) }
            val stock = actionButton("Stock") { sendTune(t, null) }
            for (bt in listOf(minus, plus, set, stock)) { bt.isEnabled = ok; bt.alpha = if (ok) 1f else 0.35f }
            r.addView(valueTile, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 3f).apply { setMargins(dp(3), dp(3), dp(3), dp(3)) })
            for (bt in listOf(minus, plus, set, stock))
                r.addView(bt, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply { setMargins(dp(3), dp(6), dp(3), dp(6)) })
            col.addView(r, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(76)))
        }
    }

    /** Current setting as shown (null = stock or not read). */
    private fun tuneNow(t: Proto.Tunable): Double? = v(t.key)?.takeIf { it != 0.0 }

    private fun stepTune(t: Proto.Tunable, d: Double) {
        val cur = tuneNow(t) ?: return setTuneDialog(t)
        sendTune(t, cur + d)
    }

    private fun sendTune(t: Proto.Tunable, value: Double?) {
        if (engine.ecuFeat and Proto.F_TUNING == 0) return toast("Needs ECU file v5.3 or newer")
        if (engine.state != Engine.State.CONNECTED) return toast("Connect first")
        if (value != null) {
            val err = Proto.check(t, value) { id -> Proto.tunables.firstOrNull { it.id == id }?.let { tuneNow(it) } }
            if (err != null) { confirmSound(false); return toast(err) }
        }
        engine.tune(t, value) { ok -> ui.post {
            confirmSound(ok)
            toast(if (ok) t.name + ": " + (if (value == null) "stock" else Proto.fmt(value) + " " + t.unit) else t.name + ": no reply from ECU")
        } }
    }

    private fun setTuneDialog(t: Proto.Tunable) {
        val input = EditText(this).apply {
            hint = "${Proto.fmt(t.min)} to ${Proto.fmt(t.max)} ${t.unit}"
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL or (if (t.signed) InputType.TYPE_NUMBER_FLAG_SIGNED else 0)
            setSingleLine()
            tuneNow(t)?.let { setText(Proto.fmt(it)) }
        }
        AlertDialog.Builder(this).setTitle(t.name).setView(input)
            .setPositiveButton("Apply") { _, _ ->
                val x = input.text.toString().replace(',', '.').toDoubleOrNull()
                if (x == null) toast("Enter a number") else sendTune(t, x)
            }
            .setNegativeButton("Cancel", null).show()
    }

    // ------------------------------------------------------------------ other screens

    private fun buildDiag(b: LinearLayout) {
        val out = TextView(this).apply { setTextColor(Theme.TEXT); textSize = 14f; typeface = Typeface.MONOSPACE; setPadding(dp(8), dp(8), dp(8), dp(8)) }
        terminalOut = out
        val input = EditText(this).apply {
            hint = "Command, e.g. ATRV or 22D742"
            setTextColor(Theme.TEXT); setHintTextColor(Theme.DIM2); textSize = 16f
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
            setSingleLine()
        }
        row(b, 0.9f, actionButton("Read codes") { readCodes() }, actionButton("Clear codes") { confirmClear() },
            actionButton("Adapter volts") { terminal("ATRV") }, actionButton("Adapter info") { terminal("ATI") })
        val line = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        line.addView(input, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 4f))
        line.addView(actionButton("Send") { val c = input.text.toString().trim(); if (c.isNotEmpty()) terminal(c) },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        b.addView(line, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 0.8f))
        b.addView(panel(out), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 4f))
    }

    private fun toggle(label: String, key: String, def: Boolean, after: (() -> Unit)? = null): Button {
        val btn = actionButton("") {}
        fun show() { btn.text = label + ": " + (if (prefs.getBoolean(key, def)) "ON" else "OFF") }
        show()
        btn.setOnClickListener { click(); prefs.edit().putBoolean(key, !prefs.getBoolean(key, def)).apply(); show(); after?.invoke() }
        return btn
    }

    @SuppressLint("MissingPermission")
    private fun buildSettings(b: LinearLayout) {
        val list = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val names = ArrayList<Pair<String, String>>()
        try {
            val bm = getSystemService(BluetoothManager::class.java)
            bm?.adapter?.bondedDevices?.forEach { d -> names.add((d.name ?: "Unknown") to d.address) }
        } catch (e: SecurityException) {
            Dash.addEvent("Bluetooth permission missing")
        }
        val saved = prefs.getString("addr", null)
        for ((n, a) in names) {
            list.addView(actionButton((if (a == saved) "* " else "") + n + "\n" + a) {
                prefs.edit().putString("addr", a).putBoolean("demo", false).putInt("sock", 0).apply()
                Dash.startService(this, DashService.ACTION_CONNECT)
            }, LinearLayout.LayoutParams(dp(220), ViewGroup.LayoutParams.MATCH_PARENT).apply { setMargins(dp(4), dp(4), dp(4), dp(4)) })
        }
        list.addView(actionButton("Demo (no car)") {
            prefs.edit().putBoolean("demo", true).apply()
            Dash.startService(this, DashService.ACTION_CONNECT)
        }, LinearLayout.LayoutParams(dp(220), ViewGroup.LayoutParams.MATCH_PARENT).apply { setMargins(dp(4), dp(4), dp(4), dp(4)) })
        val hs = HorizontalScrollView(this).apply { addView(list) }
        b.addView(note("Paired adapters (pair the OBD adapter in Android Bluetooth settings first, PIN usually 1234):"),
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 0.4f))
        b.addView(hs, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        val fast = actionButton(if (engine.fastMode) "Fast mode: ON" else "Fast mode: OFF") {}
        fast.setOnClickListener {
            click()
            engine.fastMode = !engine.fastMode
            prefs.edit().putBoolean("fast", engine.fastMode).apply()
            fast.text = if (engine.fastMode) "Fast mode: ON" else "Fast mode: OFF"
        }
        row(b, 0.7f, toggle("Auto connect", "auto", true) { if (Dash.wants(this)) startAuto() }, fast,
            actionButton("Exit app") { exitApp() })
        row(b, 0.7f, toggle("Connect sound", "snd_connect", true), toggle("Alarm sound", "snd_alarm", true),
            toggle("Button sounds", "snd_buttons", true), actionButton("Test sounds") { testSounds() })
        row(b, 0.8f, *Theme.palettes.map { pal ->
            Button(this).apply {
                text = pal.name
                val on = pal.id == Theme.current.id
                styled(this, 14f, if (on) Theme.TEXT else pal.accent,
                    Theme.fill(Theme.withAlpha(pal.accent, if (on) 90 else 24), dpf(12f), Theme.withAlpha(pal.accent, if (on) 255 else 110), if (on) dp(2) else 1))
                setOnClickListener { click(); applyTheme(pal.id) }
            }
        }.toTypedArray())
        val ev = TextView(this).apply { setTextColor(Theme.DIM); textSize = 13f; typeface = Typeface.MONOSPACE; setPadding(dp(8), dp(8), dp(8), dp(8)) }
        eventsView = ev
        refreshEvents()
        b.addView(panel(ev), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 2.3f))
        b.addView(note("Honda Dash " + versionName() + "   Auto connect: connects on app start, after a restart and after the ignition was off. Disconnect pauses it until the app is opened again."),
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 0.4f))
    }

    private fun testSounds() {
        val t = try { ToneGenerator(AudioManager.STREAM_MUSIC, 100) } catch (_: Exception) { null } ?: return toast("Sound not available")
        DashService.playConnect(t)
        ui.postDelayed({ DashService.playAlarm(t) }, 900)
        ui.postDelayed({ t.release() }, 2000)
    }

    private fun exitApp() {
        confirm("Exit Honda Dash?", "This disconnects until you open the app again.") {
            Dash.startService(this, DashService.ACTION_EXIT)
            finish()
        }
    }

    private fun confirm(title: String, msg: String, ok: () -> Unit) {
        AlertDialog.Builder(this).setTitle(title).setMessage(msg)
            .setPositiveButton("OK") { _, _ -> ok() }
            .setNegativeButton("Cancel", null).show()
    }

    private fun fillUpDialog() {
        val t = Dash.trip ?: return
        val input = EditText(this).apply {
            hint = "Litres added"
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            setSingleLine()
        }
        AlertDialog.Builder(this).setTitle("Fill-up to the top")
            .setMessage(String.format("This tank: %.0f km since Full tank now. Enter the litres that went in.", t.tankKm))
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                val l = input.text.toString().replace(',', '.').toDoubleOrNull()
                val avg = if (l == null) null else t.fillUp(l)
                if (avg == null) toast("Not saved: needs at least ${Trip.MIN_CAL_KM.toInt()} km since Full tank now and a valid litre figure")
                else {
                    Dash.addEvent(String.format("Fill-up %.1f L: tank average %.1f km/L. Fuel figures calibrated.", l, avg))
                    toast(String.format("Tank average %.1f km/L. Calibrated.", avg))
                    showPage(currentPage)
                }
            }
            .setNegativeButton("Cancel", null).show()
    }

    private fun buildLogs(b: LinearLayout) {
        row(b, 0.8f, actionButton("Share latest") { CsvLogger.list(this).firstOrNull()?.let { share(it) } ?: toast("No logs yet") },
            actionButton("Copy all to Downloads") { exportAll() },
            actionButton("Refresh") { showPage(currentPage) })
        val files = CsvLogger.list(this)
        b.addView(note(if (files.isEmpty()) "No logs yet. Press Start log on any screen, drive, then Stop log."
            else "${files.size} logs. Tap Share to send one to WhatsApp, Gmail, Drive or a file manager."),
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 0.4f))
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        for (f in files.take(60)) {
            val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            val kb = (f.length() + 1023) / 1024
            r.addView(note(f.name.removePrefix("HondaDash_").removeSuffix(".csv") + "   " + kb + " KB").apply { setTextColor(Theme.TEXT) },
                LinearLayout.LayoutParams(0, dp(52), 3f))
            r.addView(actionButton("Share") { share(f) }, LinearLayout.LayoutParams(0, dp(46), 1f).apply { setMargins(dp(3), dp(3), dp(3), dp(3)) })
            r.addView(actionButton("Delete") { confirmDelete(f) }, LinearLayout.LayoutParams(0, dp(46), 1f).apply { setMargins(dp(3), dp(3), dp(3), dp(3)) })
            list.addView(r, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        b.addView(panel(list), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 4f))
    }

    private fun share(f: File) {
        try {
            val uri = LogProvider.uriFor(f)
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "text/csv"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, f.name)
                clipData = ClipData.newRawUri(f.name, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(send, "Share " + f.name).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
        } catch (e: Exception) {
            toast("Share failed: ${e.javaClass.simpleName} ${e.message}")
        }
    }

    private fun confirmDelete(f: File) {
        confirm("Delete this log?", f.name) { f.delete(); showPage(currentPage) }
    }

    private fun exportAll() {
        val files = CsvLogger.list(this)
        if (files.isEmpty()) return toast("No logs yet")
        Thread {
            var ok = 0
            var err = ""
            for (f in files) {
                try { CsvLogger.exportToDownloads(this, f); ok++ } catch (e: Exception) { err = "${e.javaClass.simpleName}: ${e.message}" }
            }
            ui.post {
                val msg = if (err.isEmpty()) "Copied $ok logs to Download/HondaDash" else "Copied $ok of ${files.size}. Downloads error: $err"
                Dash.addEvent(msg)
                Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
            }
        }.start()
    }

    private fun versionName(): String = try { packageManager.getPackageInfo(packageName, 0).versionName ?: "" } catch (_: Exception) { "" }

    // ------------------------------------------------------------------ actions

    private fun onConnectPressed() {
        if (Dash.active()) {
            Dash.startService(this, DashService.ACTION_DISCONNECT)
        } else {
            if (prefs.getString("addr", null) == null && !prefs.getBoolean("demo", false)) {
                toast("Pick the OBD adapter in Settings")
                showPage(pages.indexOfFirst { it.title == "Settings" })
                return
            }
            Dash.startService(this, DashService.ACTION_CONNECT)
        }
    }

    private fun sendCommand(label: String, code: String) {
        if (engine.state != Engine.State.CONNECTED) return toast("Not connected")
        engine.post { elm ->
            val ok = elm.command(code)
            ui.post { confirmSound(ok); toast(if (ok) "$label: done" else "$label: no reply from ECU") }
        }
    }

    private fun terminal(c: String) {
        if (engine.state != Engine.State.CONNECTED) return toast("Not connected")
        appendTerminal("> $c")
        engine.post { elm ->
            val r = elm.raw(c)
            ui.post { appendTerminal(r.replace("\r", "\n").trim()) }
        }
    }

    private fun readCodes() {
        if (engine.state != Engine.State.CONNECTED) return toast("Not connected")
        appendTerminal("> Read codes")
        engine.post { elm ->
            val codes = elm.readCodes()
            ui.post { appendTerminal(if (codes.isEmpty()) "No stored engine codes" else codes.joinToString("  ")) }
        }
    }

    private fun confirmClear() {
        confirm("Clear engine codes?", "This also resets the ECU's learned fuel trims and readiness monitors.") {
            engine.post { elm -> val ok = elm.clearCodes(); ui.post { appendTerminal(if (ok) "Codes cleared" else "Clear: no reply") } }
        }
    }

    private fun startLog() {
        if (engine.state != Engine.State.CONNECTED) return toast("Connect first")
        val keys = (pages[currentPage].keys() + CORE_LOG).distinct()
        try {
            Dash.startLog(this, keys, pages[currentPage].title.replace(' ', '_').replace("&", "and"))
        } catch (e: Exception) {
            val msg = "Log failed: ${e.javaClass.simpleName} ${e.message}"
            Dash.addEvent(msg)
            Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
        }
    }

    private fun stopLog() = Dash.stopLog(this)

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    private fun onEvent(s: String) {
        refreshEvents()
        if (resumed && (s.startsWith("Connected") || s.startsWith("Link") || s.startsWith("Saved") || s.startsWith("Logging") || s.startsWith("Disconnected")))
            toast(s)
    }

    private fun refreshEvents() { eventsView?.text = Dash.events.takeLast(80).reversed().joinToString("\n") }

    private fun appendTerminal(s: String) {
        val o = terminalOut ?: return
        o.append(s + "\n")
    }

    // ------------------------------------------------------------------ refresh loops

    /** 20 times a second: needles and the rpm bar pick up new readings at once. */
    private val fastTick = object : Runnable {
        override fun run() {
            val now = System.currentTimeMillis()
            for (g in gauges) g.update(fresh(g.key, 15000, now), engine.stamps[g.key] ?: 0L, now)
            shiftBar.update(fresh("RPM_E", 5000, now), engine.stamps["RPM_E"] ?: 0L, now)
            ui.postDelayed(this, 50)
        }
    }

    /** 4 times a second: cards, buttons, status and alarms. */
    private val slowTick = object : Runnable {
        override fun run() {
            refresh()
            ui.postDelayed(this, 250)
        }
    }

    /** Latest value of [key] if it was read in the last [maxAgeMs], else null. */
    private fun fresh(key: String, maxAgeMs: Long, now: Long): Double? {
        val st = engine.stamps[key] ?: return null
        if (now - st > maxAgeMs) return null
        return engine.values[key]?.takeIf { !it.isNaN() }
    }

    private fun refresh() {
        val v = engine.values
        val now = System.currentTimeMillis()
        val st = engine.state
        if (st != lastState) {
            lastState = st
            val (txt, col) = when (st) {
                Engine.State.CONNECTED -> "CONNECTED" to Theme.OK
                Engine.State.CONNECTING -> "CONNECTING" to Theme.WARN
                Engine.State.WAITING -> "WAITING FOR CAR" to Theme.WARN
                Engine.State.ERROR -> "NOT CONNECTED" to Theme.BAD
                Engine.State.IDLE -> "NOT CONNECTED" to Theme.BAD
            }
            status.text = txt
            status.setTextColor(col)
            pill.background = Theme.fill(Theme.withAlpha(col, 28), dpf(12f), Theme.withAlpha(col, 90))
            pillDot.background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(col) }
        }
        // the ECU file's features were read after this page was built: rebuild so the new controls appear
        if (engine.ecuFeat != builtFeat && pages[currentPage].title in setOf("Graph", "Tune")) { showPage(currentPage); return }
        val label = if (Dash.active()) "Disconnect" else "Connect"
        if (connectBtn.text.toString() != label) connectBtn.text = label
        rate.text = if (st != Engine.State.CONNECTED) "" else if (engine.snapshotActive)
            String.format("FAST DATA  %.0f values/s", engine.valuesPerSecond)
            else String.format("%.0f values/s  %.1f s", engine.valuesPerSecond, engine.cycleMs / 1000.0)
        val lg = engine.logger
        if (lg != null) {
            if (logStart == 0L) logStart = now
            val sec = ((now - logStart) / 1000).toInt()
            logBtn.text = String.format("REC %02d:%02d", sec / 60, sec % 60)
            if (!loggingShown) {
                loggingShown = true
                logBtn.setTextColor(Color.rgb(252, 165, 165))
                logBtn.background = Theme.fill(Color.argb(38, 239, 68, 68), dpf(12f), Color.argb(150, 239, 68, 68))
            }
        } else if (loggingShown) {
            loggingShown = false
            logStart = 0L
            logBtn.text = "Start log"
            logBtn.setTextColor(Theme.TEXT)
            logBtn.background = keyBg()
        }

        // cards and lamps: values older than 15 s show as stale
        for ((t, f) in tiles) {
            val x = v[t.key]
            t.numeric = x
            t.text = f(x)
            val s = engine.stamps[t.key]
            t.stale = Pids[t.key] != null && (s == null || now - s > 15000)
            t.invalidate()
        }
        for (l in lamps) l.on = v[l.key]?.let { it >= 0.5 }
        for (bv in bars) bv.value = v[bv.key]
        for ((sv, state) in segments) sv.selected = state()
        val ch = chart
        if (ch != null && now - lastChart > 500) {
            lastChart = now
            ch.invalidate()
            val n = ch.keys.count { Pids[it] != null }
            graphInfo?.text = if (st == Engine.State.CONNECTED) String.format("%d values, each updated every %.1f s", n, engine.cycleMs / 1000.0)
                else "$n values selected"
            recStatus?.text = when (v("REC_STATE")?.toInt()) {
                1 -> "Recorder: armed"
                2 -> "Recorder: recording"
                3 -> "Recorder: done, " + (v("REC_COUNT")?.toInt() ?: 0) + " samples. Press Download."
                else -> "10 ms recorder: ready"
            }
        }

        // rpm flash: VTEC engaged or above 5000 rpm
        val rpm = fresh("RPM_E", 5000, now)
        val vtec = fresh("VTEC", 5000, now)
        val flash = (rpm != null && rpm > 5000) || (vtec != null && vtec >= 0.5)
        shiftBar.flash = flash
        rpmGauge?.let { it.flash = flash; it.badge = if (it.peak > 0) "MAX " + Math.round(it.peak) else "MAX --" }
        speedGauge?.let { it.badge = if (it.peak >= 0) "TOP " + Math.round(it.peak) else "TOP --" }
        val ect = fresh("ECT_F", 30000, now)
        coolantGauge?.let {
            it.alarm = ect != null && ect > DashService.HOT_F
            it.badge = when { ect == null -> "STANDBY"; ect > DashService.HOT_F -> "HOT"; ect < 160 -> "COLD"; else -> "NORMAL" }
        }
        // alarm: header blinks red (the service plays the sound)
        val alarm = (ect != null && ect > DashService.HOT_F) || (rpm != null && rpm > DashService.HIGH_RPM)
        val blink = alarm && (now / 500) % 2 == 0L
        if (blink != headerRed) {
            headerRed = blink
            header.background = if (blink) Theme.fill(Color.rgb(120, 10, 16), dpf(14f), Theme.BAD)
                else Theme.fill(Theme.withAlpha(Theme.BAR, 235), dpf(14f), Color.argb(26, 255, 255, 255))
        }
    }
}
