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
    private val tabButtons = ArrayList<Button>()
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
    private var rpmGauge: GaugeView? = null
    private var speedGauge: GaugeView? = null
    private var coolantGauge: GaugeView? = null
    private var currentPage = 0
    private var lastState: Engine.State? = null

    private val CORE_LOG = listOf("RPM_E", "SPEED", "TPS", "GEAR", "ECT_F", "T_FIN")

    private class Page(val title: String, val keys: List<String>, val fast: Set<String> = emptySet(), val build: (LinearLayout) -> Unit)
    private lateinit var pages: List<Page>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Dash.prefs(this)
        Theme.set(prefs.getString("theme", "papaya"))
        uiScale = if (resources.configuration.smallestScreenWidthDp >= 600) 1.2f else 1f
        // first start after the update: keep auto connect on if an adapter was already chosen
        if (!prefs.contains("want")) prefs.edit().putBoolean("want", prefs.getString("addr", null) != null || prefs.getBoolean("demo", false)).apply()
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
        if (need.isNotEmpty()) requestPermissions(need.toTypedArray(), 1) else Dash.startService(this)
        ui.post(tick)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        Dash.startService(this)
    }

    @Suppress("DEPRECATION")
    override fun onResume() {
        super.onResume()
        resumed = true
        window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_LAYOUT_STABLE)
        // the head unit woke up: make sure the service runs and retries at once
        if (prefs.getBoolean("want", false)) {
            Dash.startService(this)
            if (engine.state == Engine.State.WAITING) engine.retryNow()
        }
    }

    override fun onPause() {
        resumed = false
        super.onPause()
    }

    override fun onDestroy() {
        ui.removeCallbacks(tick)
        Dash.listener = null
        super.onDestroy()
    }

    // ------------------------------------------------------------------ layout

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
    private fun dpf(v: Float): Float = v * resources.displayMetrics.density

    private fun onAccent(): Int {
        val a = Theme.ACCENT
        val lum = (0.299 * Color.red(a) + 0.587 * Color.green(a) + 0.114 * Color.blue(a)) / 255
        return if (lum > 0.6) Theme.BG else Color.WHITE
    }

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

    private fun buildRoot(): View {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val a = Theme.ACCENT
        root.background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(
            Color.rgb((9 + Color.red(a) * 0.09).toInt(), (11 + Color.green(a) * 0.09).toInt(), (16 + Color.blue(a) * 0.09).toInt()),
            Color.rgb(9, 11, 16), Color.rgb(5, 6, 8)))

        shiftBar = ShiftBarView(this)
        root.addView(shiftBar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(8)))

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
        // theme dots
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
            d.setOnClickListener { applyTheme(pal.id) }
            dots.addView(d, LinearLayout.LayoutParams(dp(20), dp(20)).apply { setMargins(dp(4), 0, dp(4), 0) })
        }
        header.addView(dots, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT).apply { setMargins(0, dp(2), dp(8), dp(2)) })
        logBtn = Button(this).apply { text = "Start log"; setOnClickListener { if (engine.logger == null) startLog() else stopLog() } }
        styled(logBtn, 15f, Theme.TEXT, Theme.fill(Color.argb(16, 255, 255, 255), dpf(12f), Color.argb(26, 255, 255, 255)))
        connectBtn = Button(this).apply { text = "Connect"; setOnClickListener { onConnectPressed() } }
        styled(connectBtn, 15f, onAccent(), Theme.fill(Theme.ACCENT, dpf(12f)))
        header.addView(logBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT).apply { setMargins(0, 0, dp(8), 0) })
        header.addView(connectBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT))
        root.addView(header, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)).apply { setMargins(dp(6), dp(4), dp(6), 0) })

        content = FrameLayout(this)
        root.addView(content, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        val tabs = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(4), dp(4), dp(4), dp(4))
            background = Theme.fill(Theme.withAlpha(Theme.BAR, 242), dpf(14f), Color.argb(26, 255, 255, 255))
        }
        tabButtons.clear()
        pages.forEachIndexed { i, p ->
            val b = Button(this).apply { text = p.title; setOnClickListener { showPage(i) } }
            styled(b, 15f * uiScale, Theme.DIM, Theme.fill(Color.TRANSPARENT, dpf(10f)))
            b.setPadding(dp(2), 0, dp(2), 0)
            b.isSingleLine = uiScale > 1f  // head unit: one line; phone: may wrap to two lines as before
            tabButtons.add(b)
            tabs.addView(b, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply { setMargins(dp(2), 0, dp(2), 0) })
        }
        root.addView(tabs, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(if (uiScale > 1f) 62 else 56)).apply { setMargins(dp(6), 0, dp(6), dp(6)) })
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
        gauges.clear(); tiles.clear(); lamps.clear(); bars.clear()
        rpmGauge = null; speedGauge = null; coolantGauge = null
        eventsView = null; terminalOut = null
        content.removeAllViews()
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(4), dp(4), dp(4), dp(4)) }
        pages[i].build(body)
        content.addView(body, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        tabButtons.forEachIndexed { k, b ->
            val on = k == i
            b.setTextColor(if (on) Theme.ACCENT else Theme.DIM)
            b.background = if (on) Theme.fill(Theme.withAlpha(Theme.ACCENT, 36), dpf(10f), Theme.withAlpha(Theme.ACCENT, 120))
                else Theme.fill(Color.TRANSPARENT, dpf(10f))
        }
        engine.fast = pages[i].fast
        engine.wanted = pages[i].keys.toSet()
    }

    /** A horizontal row with equal-width cells. [weight] is the row's share of the page height. */
    private fun row(parent: LinearLayout, weight: Float, vararg views: View) {
        val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        for (v in views) r.addView(v, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply { setMargins(dp(3), dp(3), dp(3), dp(3)) })
        parent.addView(r, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, weight))
    }

    private fun gauge(key: String, title: String, unit: String, min: Double, max: Double, major: Double, minor: Int,
                      red: Double? = null, label: (Double) -> String = { Math.round(it).toString() }): GaugeView =
        GaugeView(this, key, title, unit, min, max, major, minor, red, 0, label).also { gauges.add(it) }

    private fun tile(key: String, label: String? = null, above: Double? = null, below: Double? = null, tag: String = "",
                     style: Int = TileView.NUMBER, barMax: Double? = null, text: ((Double?) -> String)? = null): TileView {
        val p = Pids[key]
        val unit = if (key == Derived.TGAP) "deg" else p?.unit ?: ""
        val t = TileView(this, key, label ?: p?.short ?: (Derived.names[key] ?: key), unit, above, below, tag, style, barMax)
        val f: (Double?) -> String = text ?: { v ->
            if (p != null) p.format(v) else if (v == null || v.isNaN()) "--" else String.format("%.1f", v)
        }
        tiles.add(t to f)
        return t
    }

    private fun lamp(key: String, label: String? = null): LampView =
        LampView(this, key, label ?: Pids[key]?.short ?: key).also { lamps.add(it) }

    private fun bar(key: String, label: String? = null): BarView =
        BarView(this, key, label ?: Pids[key]?.short ?: key, -30.0, 30.0).also { bars.add(it) }

    private fun cmdButton(label: String, code: String): Button = Button(this).apply {
        text = label
        styled(this, 16f, Theme.TEXT, Theme.fill(Theme.withAlpha(Theme.ACCENT, 34), dpf(12f), Theme.withAlpha(Theme.ACCENT, 110)))
        setOnClickListener { sendCommand(label, code) }
    }

    private fun actionButton(label: String, onClick: () -> Unit): Button = Button(this).apply {
        text = label
        styled(this, 16f, Theme.TEXT, Theme.fill(Color.argb(16, 255, 255, 255), dpf(12f), Color.argb(26, 255, 255, 255)))
        setOnClickListener { onClick() }
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

    // ------------------------------------------------------------------ screens

    private val modeText: (Double?) -> String = { v -> when (v?.toInt()) { 1 -> "Eco"; 2 -> "Sport"; null -> "--"; else -> "Normal" } }
    private val popText: (Double?) -> String = { v -> when (v?.toInt()) { 1 -> "Mild"; 2 -> "Medium"; 3 -> "Full"; null -> "--"; else -> "Off" } }
    private val sigText: (Double?) -> String = { v -> when (v?.toInt()) { 2 -> "Tach"; 3 -> "Off"; null -> "--"; else -> "Speedo" } }
    private val luText: (Double?) -> String = { v -> Derived.lockupText(v) }
    private val gearText: (Double?) -> String = { v -> when (v?.toInt()) { null -> "--"; 0 -> "P/N"; 6 -> "R"; else -> v.toInt().toString() } }

    private fun buildPages(): List<Page> = listOf(
        Page("Drive", listOf("RPM_E", "SPEED", "ECT_F", "GEAR", "MODE", "STFT", "LTFT", "T_FIN", "T_MAP", "TPS", "IAT", "LU_STATE", "POP_LVL", "VTEC"),
            setOf("RPM_E", "SPEED", "VTEC")) { b ->
            val r = gauge("RPM_E", "Tachometer x1000", "RPM", 0.0, 8000.0, 1000.0, 5, 6500.0) { (Math.round(it) / 1000).toString() }
            val s = gauge("SPEED", "Speedometer", "KM/H", 0.0, 240.0, 20.0, 4)
            val c = gauge("ECT_F", "Coolant temp", "COOLANT F", 100.0, 260.0, 20.0, 4, 225.0)
            rpmGauge = r; speedGauge = s; coolantGauge = c
            // short screens (phone in landscape): smaller dials, taller cards
            row(b, if (resources.configuration.screenHeightDp < 500) 2.2f else 2.7f, r, s, c)
            row(b, 1f, tile("GEAR", "Gear", tag = "5AT", style = TileView.ACCENT, text = gearText),
                tile("MODE", "Mode", tag = "ECU", style = TileView.WORD, text = modeText),
                tile("POP_LVL", "Pops", tag = "LEVEL", style = TileView.WORD, text = popText),
                tile("LU_STATE", "Lock-up", tag = "TCC", style = TileView.WORD, text = luText),
                tile(Derived.TGAP, "Timing removed", tag = "MAP-FINAL"))
            row(b, 1f, tile("STFT", "STFT", tag = "B1"), tile("LTFT", "LTFT", above = 15.0, tag = "B1"), tile("T_FIN", "Timing", tag = "IGN"),
                tile("TPS", "Throttle", tag = "TPS", barMax = 100.0), tile("IAT", "Intake", tag = "IAT"))
        },
        Page("Engine", listOf("T_MAP", "T_FIN", "TIMING", "STFT", "LTFT", "LTFT_E", "MAP", "LOAD", "ECT_F", "IAT", "LAMBDA", "IDLE_F", "VTEC", "LOOP", "FAN", "AC_CLUTCH", "RPM_E")) { b ->
            row(b, 1f, tile("RPM_E"), tile("T_MAP", "Map timing"), tile("T_FIN", "Final timing"), tile(Derived.TGAP, "Removed"), tile("TIMING", "OBD timing"))
            row(b, 1f, tile("STFT"), tile("LTFT", above = 15.0), tile("LTFT_E", "LTFT ECU"), tile("LAMBDA"), tile("MAP"))
            row(b, 1f, tile("LOAD"), tile("ECT_F", above = 225.0), tile("IAT"), tile("IDLE_F", "Idle target"))
            row(b, 0.8f, lamp("VTEC"), lamp("LOOP", "Closed loop"), lamp("FAN"), lamp("AC_CLUTCH", "A/C clutch"))
        },
        Page("Pops & modes", listOf("POP_LVL", "POP_TIMSET", "POP_WINSET", "POP_SIG", "POP_TGT", "POP_CNT", "POP_REQ", "POP_FUEL", "POP_ARM", "MODE", "SMODE", "STALL", "BTN_SHIFT", "FAN", "FAN_OVR", "RPM_E", "T_FIN", "GEAR")) { b ->
            row(b, 1f, tile("POP_LVL", "Pops", style = TileView.WORD, text = popText), tile("POP_TGT", "Pop timing"), tile("POP_CNT", "Pop count"),
                tile("POP_SIG", "Signal", style = TileView.WORD, text = sigText), tile("MODE", "Mode", style = TileView.WORD, text = modeText), tile("T_FIN", "Timing"))
            row(b, 0.9f, cmdButton("Pops off", "230700"), cmdButton("Mild", "230701"), cmdButton("Medium", "230702"), cmdButton("Full", "230703"),
                cmdButton("Signal on", "230A00"), cmdButton("Signal off", "230A03"))
            row(b, 0.9f, stepper("Pop timing", "POP_TIMSET", "2308", 0, 20, 1, "deg"), stepper("Pop window", "POP_WINSET", "2309", 0, 40, 5, "x0.1 s"))
            row(b, 0.9f, cmdButton("Eco", "230301"), cmdButton("Normal", "230300"), cmdButton("Sport", "230302"),
                cmdButton("Button shift", "230202"), cmdButton("Fans", "230102"))
            row(b, 0.8f, lamp("POP_REQ", "Cut req"), lamp("POP_FUEL", "Fuel kept"), lamp("POP_ARM", "Armed"), lamp("SMODE", "S mode"),
                lamp("STALL", "Stall shift"), lamp("FAN", "Fan"))
        },
        Page("Gearbox", listOf("GB_RPM", "MAINSHAFT", "COUNTERSHAFT", "GEAR", "LU_STATE", "TBYTE", "TPS", "SPEED", "SMODE", "X_SHIFT_SCHEDULE_ROW")) { b ->
            row(b, 1.3f, tile("GB_RPM", "Engine rpm"), tile("MAINSHAFT", "Mainshaft"), tile(Derived.SLIP, "Converter slip %", above = 30.0),
                tile("LU_STATE", "Lock-up", style = TileView.WORD, text = luText))
            row(b, 1f, tile("GEAR", "Gear (ECU)", style = TileView.ACCENT, text = gearText),
                tile(Derived.GEAR_CALC, "Gear (shafts)", style = TileView.ACCENT, text = { v -> if (v == null || v < 1) "--" else v.toInt().toString() }),
                tile("COUNTERSHAFT", "Countershaft"), tile("SPEED"))
            row(b, 1f, tile("TPS"), tile("TBYTE", "Throttle byte"), tile("X_SHIFT_SCHEDULE_ROW", "Shift row"), lamp("SMODE", "S mode"))
            row(b, 0.5f, note("Lock-up meanings are inferred from logs: Partial lock = light-throttle slip control, Coast lock = locked while lifting off."))
        },
        Page("Timing", listOf("T_MAP", "T_FIN", "T_D6FA", "T_D6FC", "T_D6FE", "T_D700", "T_D702", "T_D704", "T_D706", "T_D708", "T_D778", "T_D77A", "RPM_E", "TPS", "IAT", "ECT_F")) { b ->
            row(b, 1f, tile("T_MAP", "Map timing"), tile("T_FIN", "Final timing"), tile(Derived.TGAP, "Removed", above = 8.0), tile("RPM_E"), tile("TPS"))
            for (pair in listOf("T_D6FA" to "T_D6FC", "T_D6FE" to "T_D700", "T_D702" to "T_D704", "T_D706" to "T_D708", "T_D778" to "T_D77A")) {
                row(b, 0.55f, bar(pair.first, "Word " + pair.first.substring(2)), bar(pair.second, "Word " + pair.second.substring(2)))
            }
            row(b, 0.5f, note("Hunting the 14-16 deg removed at load. Log two full-throttle pulls here and send the CSV."))
        },
        Page("Fuel", listOf("FUEL_BASE", "FUEL_CORR", "FUEL_EVT", "STFT", "LTFT", "LAMBDA", "MAP", "RPM_E", "LOAD", "LOOP")) { b ->
            row(b, 1f, tile("FUEL_BASE", "Base map"), tile("FUEL_CORR", "Corrected"), tile("FUEL_EVT", "Per injection"), tile(Derived.FUEL_FLOW, "Flow index"))
            row(b, 1f, tile("STFT"), tile("LTFT", above = 15.0), tile("LAMBDA"), tile("MAP"), tile("RPM_E"))
            row(b, 0.7f, lamp("LOOP", "Closed loop"), tile("LOAD"))
            row(b, 0.6f, note("Raw fuel values from the ECU fuel chain (base map B656, corrected CE32, per-injection CF40). " +
                "Pulse width in ms and litres per 100 km appear once a log fixes the scale."))
        },
        Page("Wheels", listOf("WHEEL1", "WHEEL2", "WHEEL3", "WHEEL4", "SPEED", "CR_MAIN", "CR_RES", "CR_SET")) { b ->
            row(b, 1f, tile("WHEEL1", "Wheel 1"), tile("WHEEL2", "Wheel 2"), tile("SPEED", "OBD speed"))
            row(b, 1f, tile("WHEEL3", "Wheel 3"), tile("WHEEL4", "Wheel 4"), lamp("CR_MAIN", "Cruise MAIN"))
            row(b, 0.6f, lamp("CR_RES", "RES"), lamp("CR_SET", "SET"),
                note("Driving straight, all four wheel speeds should match within 1-2 km/h. An odd one points at the VSA sensor fault."))
        },
        Page("Diag", emptyList()) { b -> buildDiag(b) },
        Page("Logs", emptyList()) { b -> buildLogs(b) },
        Page("Settings", emptyList()) { b -> buildSettings(b) },
    )

    /** - / + control for a mode 23 setting. 0 means "use the level default". */
    private fun stepper(label: String, key: String, prefix: String, min: Int, max: Int, firstNonZero: Int, unit: String): View {
        val box = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val t = tile(key, label, text = { v -> if (v == null) "--" else if (v.toInt() == 0) "auto" else v.toInt().toString() + " " + unit })
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

    private fun toggle(label: String, key: String, def: Boolean): Button {
        val btn = actionButton("") {}
        fun show() { btn.text = label + ": " + (if (prefs.getBoolean(key, def)) "ON" else "OFF") }
        show()
        btn.setOnClickListener { prefs.edit().putBoolean(key, !prefs.getBoolean(key, def)).apply(); show() }
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
                prefs.edit().putString("addr", a).putBoolean("demo", false).putBoolean("want", true).apply()
                Dash.startService(this, DashService.ACTION_CONNECT)
            }, LinearLayout.LayoutParams(dp(220), ViewGroup.LayoutParams.MATCH_PARENT).apply { setMargins(dp(4), dp(4), dp(4), dp(4)) })
        }
        list.addView(actionButton("Demo (no car)") {
            prefs.edit().putBoolean("demo", true).putBoolean("want", true).apply()
            Dash.startService(this, DashService.ACTION_CONNECT)
        }, LinearLayout.LayoutParams(dp(220), ViewGroup.LayoutParams.MATCH_PARENT).apply { setMargins(dp(4), dp(4), dp(4), dp(4)) })
        val hs = HorizontalScrollView(this).apply { addView(list) }
        b.addView(note("Paired adapters (pair the OBD adapter in Android Bluetooth settings first, PIN usually 1234):"),
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 0.4f))
        b.addView(hs, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        val fast = actionButton(if (engine.fastMode) "Fast mode: ON" else "Fast mode: OFF") {}
        fast.setOnClickListener {
            engine.fastMode = !engine.fastMode
            prefs.edit().putBoolean("fast", engine.fastMode).apply()
            fast.text = if (engine.fastMode) "Fast mode: ON" else "Fast mode: OFF"
        }
        row(b, 0.8f, fast, toggle("Connect sound", "snd_connect", true), toggle("Alarm sound", "snd_alarm", true),
            actionButton("Test sounds") { testSounds() }, actionButton("Exit app") { exitApp() })
        row(b, 0.8f, *Theme.palettes.map { pal ->
            Button(this).apply {
                text = pal.name
                val on = pal.id == Theme.current.id
                styled(this, 14f, if (on) Theme.TEXT else pal.accent,
                    Theme.fill(Theme.withAlpha(pal.accent, if (on) 90 else 24), dpf(12f), Theme.withAlpha(pal.accent, if (on) 255 else 110), if (on) dp(2) else 1))
                setOnClickListener { applyTheme(pal.id) }
            }
        }.toTypedArray())
        val ev = TextView(this).apply { setTextColor(Theme.DIM); textSize = 13f; typeface = Typeface.MONOSPACE; setPadding(dp(8), dp(8), dp(8), dp(8)) }
        eventsView = ev
        refreshEvents()
        b.addView(panel(ev), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 2.3f))
        b.addView(note("Honda Dash " + versionName() + "   Fast mode: the adapter stops waiting after the first reply. Turn it off if values stop updating."),
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 0.4f))
    }

    private fun testSounds() {
        val t = try { ToneGenerator(AudioManager.STREAM_MUSIC, 100) } catch (_: Exception) { null } ?: return toast("Sound not available")
        DashService.playConnect(t)
        ui.postDelayed({ DashService.playAlarm(t) }, 900)
        ui.postDelayed({ t.release() }, 2000)
    }

    private fun exitApp() {
        AlertDialog.Builder(this).setTitle("Exit Honda Dash?")
            .setMessage("This disconnects and stops auto connect until you press Connect again.")
            .setPositiveButton("Exit") { _, _ ->
                Dash.startService(this, DashService.ACTION_EXIT)
                finish()
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
        AlertDialog.Builder(this).setTitle("Delete this log?").setMessage(f.name)
            .setPositiveButton("Delete") { _, _ -> f.delete(); showPage(currentPage) }
            .setNegativeButton("Cancel", null).show()
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
            prefs.edit().putBoolean("want", false).apply()
            Dash.startService(this, DashService.ACTION_DISCONNECT)
        } else {
            if (prefs.getString("addr", null) == null && !prefs.getBoolean("demo", false)) {
                toast("Pick the OBD adapter in Settings")
                showPage(pages.indexOfFirst { it.title == "Settings" })
                return
            }
            prefs.edit().putBoolean("want", true).apply()
            Dash.startService(this, DashService.ACTION_CONNECT)
        }
    }

    private fun sendCommand(label: String, code: String) {
        if (engine.state != Engine.State.CONNECTED) return toast("Not connected")
        engine.post { elm ->
            val ok = elm.command(code)
            ui.post { toast(if (ok) "$label: done" else "$label: no reply from ECU") }
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
        AlertDialog.Builder(this).setTitle("Clear engine codes?")
            .setMessage("This also resets the ECU's learned fuel trims and readiness monitors.")
            .setPositiveButton("Clear") { _, _ ->
                engine.post { elm -> val ok = elm.clearCodes(); ui.post { appendTerminal(if (ok) "Codes cleared" else "Clear: no reply") } }
            }
            .setNegativeButton("Cancel", null).show()
    }

    private fun startLog() {
        if (engine.state != Engine.State.CONNECTED) return toast("Connect first")
        val keys = (pages[currentPage].keys + CORE_LOG).distinct()
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

    // ------------------------------------------------------------------ refresh loop

    private val tick = object : Runnable {
        override fun run() {
            refresh()
            ui.postDelayed(this, 150)
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
        val label = if (Dash.active()) "Disconnect" else "Connect"
        if (connectBtn.text.toString() != label) connectBtn.text = label
        rate.text = if (st == Engine.State.CONNECTED) String.format("%.1f req/s  %.1f s", engine.requestsPerSecond, engine.cycleMs / 1000.0) else ""
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
            logBtn.background = Theme.fill(Color.argb(16, 255, 255, 255), dpf(12f), Color.argb(26, 255, 255, 255))
        }

        // gauges and tiles: values older than 15 s show as stale
        for (g in gauges) g.value = fresh(g.key, 15000, now)
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

        // shift lights and rpm flash: VTEC engaged or above 5000 rpm
        val rpm = fresh("RPM_E", 5000, now)
        val vtec = fresh("VTEC", 5000, now)
        val flash = (rpm != null && rpm > 5000) || (vtec != null && vtec >= 0.5)
        shiftBar.rpm = rpm
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
        val blink = alarm && (now / 400) % 2 == 0L
        if (blink != headerRed) {
            headerRed = blink
            header.background = if (blink) Theme.fill(Color.rgb(120, 10, 16), dpf(14f), Theme.BAD)
                else Theme.fill(Theme.withAlpha(Theme.BAR, 235), dpf(14f), Color.argb(26, 255, 255, 255))
        }
    }

    private var logStart = 0L
    private var loggingShown = false
    private var headerRed = false
}
