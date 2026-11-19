package app.hondadash

/** One readable value. [request] is the hex request sent to the ECU, for example "22D742" or "010C". */
class Pid(
    val key: String,
    val name: String,
    val short: String,
    val request: String,
    val equation: String,
    val unit: String,
    val min: Double,
    val max: Double,
    val decimals: Int,
    /** Group shown in the Graph screen's value picker. */
    val category: String = "Other",
) {
    val expr: Expr by lazy { Expr(equation) }

    /** Number of bytes the reply echoes before the data: mode 22 echoes 2 address bytes, mode 01 echoes the PID. */
    val echoBytes: Int get() = (request.length / 2) - 1

    fun format(v: Double?): String {
        if (v == null || v.isNaN()) return "--"
        return when (decimals) {
            0 -> Math.round(v).toString()
            1 -> String.format("%.1f", v)
            else -> String.format("%.2f", v)
        }
    }
}

object Pids {
    val byKey: Map<String, Pid> = PidTable.all.associateBy { it.key }
    operator fun get(key: String): Pid? = byKey[key]
}

/** Values the app works out itself from other values. */
object Derived {
    const val SLIP = "D_SLIP"          // torque converter slip, percent
    const val LU_TEXT = "D_LU"         // lock-up state as text index
    const val GEAR_CALC = "D_GEAR"     // gear from mainshaft / countershaft ratio
    const val TGAP = "D_TGAP"          // map timing minus final timing, degrees
    const val FUEL_FLOW = "D_FFLOW"    // fuel per injection x rpm, raw flow index until the scale is proven
    const val RATE = "D_RATE"          // adapter requests per second, logged so link speed is visible
    const val FUEL_LH = "D_FUEL_LH"    // fuel flow now, litres per hour (trip computer)
    const val KML = "D_KML"            // fuel economy now, km per litre (trip computer)
    val names = mapOf(SLIP to "Converter slip(%)", GEAR_CALC to "Gear (from shafts)",
        TGAP to "Timing removed(deg)", FUEL_FLOW to "Fuel flow index (raw)", RATE to "Link rate(req/s)",
        FUEL_LH to "Fuel flow(L/h)", KML to "Fuel economy now(km/L)")
    val units = mapOf(SLIP to "%", TGAP to "deg", FUEL_LH to "L/h", KML to "km/L", RATE to "req/s")
    /** Derived values offered on the Graph screen. */
    val graphKeys = listOf(FUEL_LH, KML, TGAP, SLIP, GEAR_CALC)

    // Mainshaft / countershaft ratio per gear, proven from Log C1 (17 Nov 2026).
    private val ratios = doubleArrayOf(2.656, 1.516, 1.082, 0.772, 0.566)

    /**
     * Works out the derived values from inputs read in the current poll cycle only ([live]).
     * A value left over from another screen is never used, and a derived value whose inputs are not live is removed.
     */
    fun compute(v: MutableMap<String, Double>, live: Set<String>) {
        fun g(k: String): Double? = if (k in live) v[k]?.takeIf { !it.isNaN() } else null
        val eng = g("GB_RPM")
        val main = g("MAINSHAFT")
        val cs = g("COUNTERSHAFT")
        val tm = g("T_MAP")
        val tf = g("T_FIN")
        if (tm != null && tf != null) v[TGAP] = tm - tf else v.remove(TGAP)
        val fe = g("FUEL_EVT")
        val rpm = g("RPM_E")
        val cut = g("DFCO")
        if (fe != null && rpm != null) v[FUEL_FLOW] = if (cut != null && cut >= 0.5) 0.0 else fe * rpm / 1000.0 else v.remove(FUEL_FLOW)
        // slip only means something with the car moving in gear (in P/N the shaft readings are not updated)
        if (eng != null && main != null && eng > 300 && cs != null && cs > 150) v[SLIP] = (eng - main) / eng * 100.0 else v.remove(SLIP)
        if (main != null && cs != null && cs > 150) {
            val r = main / cs
            var best = 0
            var bestErr = 1e9
            for (i in ratios.indices) {
                val e = Math.abs(r - ratios[i]) / ratios[i]
                if (e < bestErr) { bestErr = e; best = i }
            }
            v[GEAR_CALC] = if (bestErr < 0.04) (best + 1).toDouble() else 0.0
        } else v.remove(GEAR_CALC)
    }

    /** Lock-up state byte C419. Meanings inferred from Log C2 (17 Nov 2026). */
    fun lockupText(s: Double?): String = when (s?.toInt()) {
        0 -> "Off"
        1 -> "Open"
        2 -> "Partial lock"
        3 -> "Released (coast)"
        4 -> "Coast lock"
        6 -> "Applying"
        null -> "--"
        else -> "State ${s.toInt()}"
    }
}
