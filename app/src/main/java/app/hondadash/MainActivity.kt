package app.hondadash

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.bluetooth.BluetoothManager
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
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
    private val engine = Engine { msg -> ui.post { addEvent(msg) } }
    private val ui = Handler(Looper.getMainLooper())
    private lateinit var prefs: SharedPreferences
    private lateinit var content: FrameLayout
    private lateinit var status: TextView
    private lateinit var rate: TextView
    private lateinit var connectBtn: Button
    private lateinit var logBtn: Button
    private lateinit var topBar: LinearLayout
    private val tabButtons = ArrayList<Button>()
    private val events = ArrayList<String>()
    private var eventsView: TextView? = null
    private var terminalOut: TextView? = null
    private var tone: ToneGenerator? = null
    private var lastAlarm = 0L

    // bindings of the visible screen
    private val gauges = ArrayList<GaugeView>()
    private val tiles = ArrayList<Pair<TileView, (Double?) -> String>>()
    private val lamps = ArrayList<LampView>()
    private val bars = ArrayList<BarView>()
    private var currentPage = 0

    private val CORE_LOG = listOf("RPM_E", "SPEED", "TPS", "GEAR", "ECT_F", "T_FIN")

    private class Page(val title: String, val keys: List<String>, val build: (LinearLayout) -> Unit)
    private lateinit var pages: List<Page>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("hondadash", Context.MODE_PRIVATE)
        engine.fastMode = prefs.getBoolean("fast", true)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        try { tone = ToneGenerator(AudioManager.STREAM_ALARM, 90) } catch (_: Exception) {}
        pages = buildPages()
        setContentView(buildRoot())
        showPage(prefs.getInt("page", 0).coerceIn(0, pages.size - 1))
        if (Build.VERSION.SDK_INT >= 31 && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_CONNECT), 1)
        } else autoConnect()
        ui.post(tick)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        autoConnect()
    }

    @Suppress("DEPRECATION")
    override fun onResume() {
        super.onResume()
        window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_LAYOUT_STABLE)
    }

    override fun onDestroy() {
        ui.removeCallbacks(tick)
        stopLog()
        engine.stop()
        tone?.release()
        super.onDestroy()
    }

    // ------------------------------------------------------------------ layout

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun buildRoot(): View {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Theme.BG) }
        topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), 0, dp(8), 0)
            setBackgroundColor(Color.rgb(18, 16, 20))
        }
        status = TextView(this).apply { setTextColor(Theme.TEXT); textSize = 15f }
        rate = TextView(this).apply { setTextColor(Theme.DIM); textSize = 13f; setPadding(dp(16), 0, dp(16), 0) }
        connectBtn = smallButton("Connect") { onConnectPressed() }
        logBtn = smallButton("Start log") { if (engine.logger == null) startLog() else stopLog() }
        topBar.addView(status, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply { gravity = Gravity.CENTER_VERTICAL })
        topBar.addView(rate)
        topBar.addView(logBtn)
        topBar.addView(connectBtn)
        status.gravity = Gravity.CENTER_VERTICAL
        root.addView(topBar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)))

        content = FrameLayout(this)
        root.addView(content, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        val tabs = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setBackgroundColor(Color.rgb(18, 16, 20)) }
        pages.forEachIndexed { i, p ->
            val b = Button(this).apply {
                text = p.title
                isAllCaps = false
                textSize = 15f
                setTextColor(Theme.TEXT)
                setBackgroundColor(Color.TRANSPARENT)
                setOnClickListener { showPage(i) }
            }
            tabButtons.add(b)
            tabs.addView(b, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        }
        root.addView(tabs, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(58)))
        return root
    }

    private fun smallButton(label: String, onClick: () -> Unit): Button = Button(this).apply {
        text = label
        isAllCaps = false
        textSize = 14f
        setTextColor(Theme.TEXT)
        setBackgroundColor(Color.rgb(60, 24, 20))
        setOnClickListener { onClick() }
        setPadding(dp(14), 0, dp(14), 0)
    }

    private fun showPage(i: Int) {
        currentPage = i
        prefs.edit().putInt("page", i).apply()
        gauges.clear(); tiles.clear(); lamps.clear(); bars.clear()
        eventsView = null; terminalOut = null
        content.removeAllViews()
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(6), dp(6), dp(6), dp(6)) }
        pages[i].build(body)
        content.addView(body, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        tabButtons.forEachIndexed { k, b -> b.setTextColor(if (k == i) Theme.ACCENT2 else Theme.DIM) }
        engine.wanted = pages[i].keys.toSet()
    }

    /** A horizontal row with equal-width cells. [weight] is the row's share of the page height. */
    private fun row(parent: LinearLayout, weight: Float, vararg views: View) {
        val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        for (v in views) r.addView(v, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply { setMargins(dp(3), dp(3), dp(3), dp(3)) })
        parent.addView(r, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, weight))
    }

    private fun gauge(key: String, label: String, min: Double, max: Double, red: Double? = null): GaugeView =
        GaugeView(this, key, label, min, max, red).also { gauges.add(it) }

    private fun tile(key: String, label: String? = null, above: Double? = null, below: Double? = null,
                     text: ((Double?) -> String)? = null): TileView {
        val p = Pids[key]
        val t = TileView(this, key, label ?: p?.short ?: (Derived.names[key] ?: key), p?.unit ?: "", above, below)
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
        isAllCaps = false
        textSize = 16f
        setTextColor(Theme.TEXT)
        setBackgroundColor(Color.rgb(70, 26, 22))
        setOnClickListener { sendCommand(label, code) }
    }

    private fun actionButton(label: String, onClick: () -> Unit): Button = Button(this).apply {
        text = label
        isAllCaps = false
        textSize = 16f
        setTextColor(Theme.TEXT)
        setBackgroundColor(Color.rgb(40, 40, 48))
        setOnClickListener { onClick() }
    }

    private fun note(text: String): TextView = TextView(this).apply {
        this.text = text
        setTextColor(Theme.DIM)
        textSize = 14f
        gravity = Gravity.CENTER_VERTICAL
    }

    // ------------------------------------------------------------------ screens

    private val modeText: (Double?) -> String = { v -> when (v?.toInt()) { 1 -> "Eco"; 2 -> "Sport"; null -> "--"; else -> "Normal" } }
    private val popText: (Double?) -> String = { v -> when (v?.toInt()) { 1 -> "Mild"; 2 -> "Medium"; 3 -> "Full"; null -> "--"; else -> "Off" } }
    private val sigText: (Double?) -> String = { v -> when (v?.toInt()) { 2 -> "Tach"; 3 -> "Off"; null -> "--"; else -> "Speedo" } }
    private val luText: (Double?) -> String = { v -> Derived.lockupText(v) }
    private val gearText: (Double?) -> String = { v -> when (v?.toInt()) { null -> "--"; 0 -> "P/N"; 6 -> "R"; else -> v.toInt().toString() } }

    private fun buildPages(): List<Page> = listOf(
        Page("Drive", listOf("RPM_E", "SPEED", "ECT_F", "GEAR", "MODE", "STFT", "LTFT", "T_FIN", "T_MAP", "TPS", "IAT", "LU_STATE", "POP_LVL")) { b ->
            row(b, 2.2f, gauge("RPM_E", "rpm", 0.0, 7000.0, 6300.0), gauge("SPEED", "km/h", 0.0, 200.0),
                gauge("ECT_F", "coolant F", 100.0, 260.0, 225.0))
            row(b, 1f, tile("GEAR", "Gear", text = gearText), tile("MODE", "Mode", text = modeText), tile("POP_LVL", "Pops", text = popText),
                tile("LU_STATE", "Lock-up", text = luText), tile(Derived.TGAP, "Timing removed"))
            row(b, 1f, tile("STFT"), tile("LTFT", above = 15.0), tile("T_FIN", "Timing"), tile("TPS"), tile("IAT"))
        },
        Page("Engine", listOf("T_MAP", "T_FIN", "TIMING", "STFT", "LTFT", "LTFT_E", "MAP", "LOAD", "ECT_F", "IAT", "LAMBDA", "IDLE_F", "VTEC", "LOOP", "FAN", "AC_CLUTCH", "RPM_E")) { b ->
            row(b, 1f, tile("RPM_E"), tile("T_MAP", "Map timing"), tile("T_FIN", "Final timing"), tile(Derived.TGAP, "Removed"), tile("TIMING", "OBD timing"))
            row(b, 1f, tile("STFT"), tile("LTFT", above = 15.0), tile("LTFT_E", "LTFT ECU"), tile("LAMBDA"), tile("MAP"))
            row(b, 1f, tile("LOAD"), tile("ECT_F", above = 225.0), tile("IAT"), tile("IDLE_F", "Idle target"))
            row(b, 0.8f, lamp("VTEC"), lamp("LOOP", "Closed loop"), lamp("FAN"), lamp("AC_CLUTCH", "A/C clutch"))
        },
        Page("Pops & modes", listOf("POP_LVL", "POP_TIMSET", "POP_WINSET", "POP_SIG", "POP_TGT", "POP_CNT", "POP_REQ", "POP_FUEL", "POP_ARM", "MODE", "SMODE", "STALL", "BTN_SHIFT", "FAN", "FAN_OVR", "RPM_E", "T_FIN", "GEAR")) { b ->
            row(b, 1f, tile("POP_LVL", "Pops", text = popText), tile("POP_TGT", "Pop timing"), tile("POP_CNT", "Pop count"),
                tile("POP_SIG", "Signal", text = sigText), tile("MODE", "Mode", text = modeText), tile("T_FIN", "Timing"))
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
                tile("LU_STATE", "Lock-up", text = luText))
            row(b, 1f, tile("GEAR", "Gear (ECU)", text = gearText), tile(Derived.GEAR_CALC, "Gear (shafts)", text = { v -> if (v == null || v < 1) "--" else v.toInt().toString() }),
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
        val box = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setBackgroundColor(Theme.PANEL); gravity = Gravity.CENTER_VERTICAL }
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
        box.addView(t, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 2f))
        box.addView(plus, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        return box
    }

    private fun buildDiag(b: LinearLayout) {
        val out = TextView(this).apply { setTextColor(Theme.TEXT); textSize = 14f; typeface = Typeface.MONOSPACE; setPadding(dp(8), dp(8), dp(8), dp(8)) }
        terminalOut = out
        val input = EditText(this).apply {
            hint = "Command, e.g. ATRV or 22D742"
            setTextColor(Theme.TEXT); setHintTextColor(Theme.DIM); textSize = 16f
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
        val sv = ScrollView(this).apply { setBackgroundColor(Theme.PANEL); addView(out) }
        b.addView(sv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 4f))
    }

    @SuppressLint("MissingPermission")
    private fun buildSettings(b: LinearLayout) {
        val list = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val names = ArrayList<Pair<String, String>>()
        try {
            val bm = getSystemService(BluetoothManager::class.java)
            bm?.adapter?.bondedDevices?.forEach { d -> names.add((d.name ?: "Unknown") to d.address) }
        } catch (e: SecurityException) {
            addEvent("Bluetooth permission missing")
        }
        val saved = prefs.getString("addr", null)
        for ((n, a) in names) {
            list.addView(actionButton((if (a == saved) "* " else "") + n + "\n" + a) {
                prefs.edit().putString("addr", a).putBoolean("demo", false).apply()
                connectTo(a)
            }, LinearLayout.LayoutParams(dp(220), ViewGroup.LayoutParams.MATCH_PARENT).apply { setMargins(dp(4), dp(4), dp(4), dp(4)) })
        }
        list.addView(actionButton("Demo (no car)") {
            prefs.edit().putBoolean("demo", true).apply()
            engine.start(DemoLink())
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
        row(b, 0.8f, fast, note("Fast mode tells the adapter to stop waiting after the first reply. Turn it off if values stop updating."))
        val ev = TextView(this).apply { setTextColor(Theme.DIM); textSize = 13f; typeface = Typeface.MONOSPACE; setPadding(dp(8), dp(8), dp(8), dp(8)) }
        eventsView = ev
        refreshEvents()
        b.addView(ScrollView(this).apply { setBackgroundColor(Theme.PANEL); addView(ev) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 2.5f))
        b.addView(note("Honda Dash " + versionName() + "   Logs: Logs tab (copies also go to Download/HondaDash)"), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 0.4f))
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
            r.addView(actionButton("Share") { share(f) }, LinearLayout.LayoutParams(0, dp(48), 1f).apply { setMargins(dp(3), dp(2), dp(3), dp(2)) })
            r.addView(actionButton("Delete") { confirmDelete(f) }, LinearLayout.LayoutParams(0, dp(48), 1f).apply { setMargins(dp(3), dp(2), dp(3), dp(2)) })
            list.addView(r, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        b.addView(ScrollView(this).apply { setBackgroundColor(Theme.PANEL); addView(list) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 4f))
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
                addEvent(msg)
                Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
            }
        }.start()
    }

    private fun versionName(): String = try { packageManager.getPackageInfo(packageName, 0).versionName ?: "" } catch (_: Exception) { "" }

    // ------------------------------------------------------------------ actions

    private fun autoConnect() {
        if (prefs.getBoolean("demo", false)) { engine.start(DemoLink()); return }
        val a = prefs.getString("addr", null)
        if (a != null) connectTo(a) else addEvent("Pick the OBD adapter in Settings")
    }

    @SuppressLint("MissingPermission")
    private fun connectTo(addr: String) {
        try {
            val bm = getSystemService(BluetoothManager::class.java)
            val dev = bm?.adapter?.getRemoteDevice(addr) ?: return toast("Bluetooth not available")
            engine.start(BluetoothLink(dev))
        } catch (e: Exception) {
            toast("Cannot connect: ${e.message}")
        }
    }

    private fun onConnectPressed() {
        if (engine.state == Engine.State.CONNECTED || engine.state == Engine.State.CONNECTING) {
            stopLog()
            engine.stop()
        } else autoConnect()
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
        val derived = listOf(Derived.TGAP, Derived.SLIP, Derived.GEAR_CALC, Derived.FUEL_FLOW)
        try {
            val lg = CsvLogger(this, keys + derived, pages[currentPage].title.replace(' ', '_').replace("&", "and"))
            engine.logKeys = keys.toSet()
            engine.logger = lg
            logBtn.text = "Stop log"
            addEvent("Logging: ${lg.fileName}")
        } catch (e: Exception) {
            val msg = "Log failed: ${e.javaClass.simpleName} ${e.message}"
            addEvent(msg)
            Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
        }
    }

    private fun stopLog() {
        val lg = engine.logger ?: return
        engine.logger = null
        engine.logKeys = emptySet()
        lg.close()
        if (::logBtn.isInitialized) logBtn.text = "Start log"
        addEvent("Saved ${lg.fileName} (${lg.rows} rows). Share it from the Logs tab." + (lg.error?.let { " Write error: $it" } ?: ""))
        val f = lg.file
        val app = applicationContext
        Thread {
            val msg = try { "Copy in " + CsvLogger.exportToDownloads(app, f) } catch (e: Exception) {
                "Downloads copy failed (${e.javaClass.simpleName}: ${e.message}). Use Share on the Logs tab."
            }
            ui.post { addEvent(msg) }
        }.start()
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    private fun addEvent(s: String) {
        val t = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date())
        events.add("$t  $s")
        while (events.size > 200) events.removeAt(0)
        refreshEvents()
        if (s.startsWith("Connect") || s.startsWith("Link") || s.startsWith("Saved") || s.startsWith("Logging")) toast(s)
    }

    private fun refreshEvents() { eventsView?.text = events.takeLast(60).reversed().joinToString("\n") }

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

    private fun refresh() {
        val v = engine.values
        val now = System.currentTimeMillis()
        status.text = when (engine.state) {
            Engine.State.CONNECTED -> "Connected  " + engine.linkLabel
            Engine.State.CONNECTING -> "Connecting to " + engine.linkLabel + " ..."
            Engine.State.ERROR -> "Not connected (error, see Settings)"
            Engine.State.IDLE -> "Not connected"
        }
        status.setTextColor(if (engine.state == Engine.State.CONNECTED) Theme.OK else Theme.WARN)
        connectBtn.text = if (engine.state == Engine.State.CONNECTED || engine.state == Engine.State.CONNECTING) "Disconnect" else "Connect"
        rate.text = if (engine.state == Engine.State.CONNECTED)
            String.format("%.1f req/s   screen refresh %.1f s", engine.requestsPerSecond, engine.cycleMs / 1000.0) +
                (engine.logger?.let { "   LOG ${it.rows}" } ?: "") else ""
        for (g in gauges) g.value = v[g.key]
        for ((t, f) in tiles) {
            val x = v[t.key]
            t.numeric = x
            t.text = f(x)
            val st = engine.stamps[t.key]
            t.stale = Pids[t.key] != null && (st == null || now - st > 8000)
            t.invalidate()
        }
        for (l in lamps) l.on = v[l.key]?.let { it >= 0.5 }
        for (bv in bars) bv.value = v[bv.key]
        // alarms
        val ect = v["ECT_F"]
        val rpm = v["RPM_E"]
        val alarm = (ect != null && ect > 225) || (rpm != null && rpm > 6350)
        topBar.setBackgroundColor(if (alarm && (now / 400) % 2 == 0L) Color.rgb(120, 10, 10) else Color.rgb(18, 16, 20))
        if (alarm && now - lastAlarm > 4000) {
            lastAlarm = now
            try { tone?.startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 300) } catch (_: Exception) {}
        }
    }
}
