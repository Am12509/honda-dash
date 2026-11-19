package app.hondadash

/**
 * Honda Dash ECU protocol, version 1. The app side of the features planned for the next ECU files
 * (v5.3 and later). An older file (v5.2 or earlier) reads 0 at [PROTO], so the app keeps to plain polling.
 *
 * RAM (zeroed at every key-on, read with mode 22):
 *   6039 protocol/version byte (0x53 = v5.3 ...), 603A feature bits, 603B recorder state,
 *   603C recorder values per sample, 603D-603E recorder samples (word), 603F snapshot slot count,
 *   6040-607F live tuning values (word per id, 0 = stock), 6080-60BF snapshot slot addresses (32 x 2 bytes).
 *
 * Commands (mode 23, reply 63 CC VV):
 *   2310NN slot count, 2311II slot pointer, 2312HH slot address high, 2313LL slot address low (then pointer + 1),
 *   2320TT recorder: 00 stop/clear, 01 record now, 02 arm at full throttle, 03 arm at lift-off,
 *   2330ID tuning id, 2331HI value high byte, 2332LO value low byte (applies; 0 = back to stock).
 *
 * Mode 22 extensions (frames of 4 data bytes, like a VIN read):
 *   220100 snapshot: frame k (1..) = "62 01 kk" + slots 2k-2 and 2k-1, 2 bytes each (bytes at HHLL and HHLL+1).
 *   2202BB recorder block BB: 15 frames, 60 bytes from byte BB*60 of the sample buffer
 *          (samples back to back, each = values-per-sample x 2 bytes, one sample every 10 ms).
 */
object Proto {
    const val PROTO = 0x6039
    const val FEAT = 0x603A
    const val SLOT_TABLE = 0x6080
    const val TUNE_TABLE = 0x6040
    const val MAX_SLOTS = 30
    const val REC_SLOTS = 8
    const val REC_SAMPLE_MS = 10

    const val F_PN_POPS = 1        // pops start at throttle close in P/N (v5.3)
    const val F_LEVEL_PULSE = 2    // speedometer pulses on every pop level change (v5.3)
    const val F_SNAPSHOT = 4       // 220100 multi-frame snapshot (v5.4)
    const val F_RECORDER = 8       // 10 ms recorder (v5.4)
    const val F_TUNING = 16        // live tuning table (v5.5)

    /** Mode 22 address of a request like "22D742", or -1 for other requests. */
    fun address(request: String): Int =
        if (request.length == 6 && request.startsWith("22")) request.substring(2).toInt(16) else -1

    private val usesCD = Regex("""\b[CD]\b""")
    private val usesAB = Regex("""\b[AB]\b""")

    /** Word slots (RAM addresses) a mode 22 value needs: base for A/B, base + 2 for C/D. */
    fun slotsFor(p: Pid): List<Int> {
        val a = address(p.request)
        if (a < 0) return emptyList()
        val out = ArrayList<Int>(2)
        if (usesAB.containsMatchIn(p.equation)) out.add(a)
        if (usesCD.containsMatchIn(p.equation)) out.add(a + 2)
        if (out.isEmpty()) out.add(a)
        return out
    }

    /** The 4 data bytes A-D of [p] from slot values (address -> 2 bytes), or null when a slot is missing. */
    fun dataFor(p: Pid, slots: Map<Int, IntArray>): IntArray? {
        val a = address(p.request)
        val out = IntArray(4)
        for (s in slotsFor(p)) {
            val v = slots[s] ?: return null
            val off = if (s == a) 0 else 2
            out[off] = v[0]; out[off + 1] = v[1]
        }
        return out
    }

    /** Values recorded by the 10 ms recorder: rpm, final and map timing, D704, throttle byte, fuel, fuel cut, pop flags. */
    val recorderSlots = listOf(0xBC9A, 0xD742, 0xB6C8, 0xD704, 0xC9CF, 0xCF40, 0xB618, 0x6026)

    /** One live-tuning setting. Value sent = shown value x [scale]. 0 means stock. */
    class Tunable(val id: Int, val key: String, val name: String, val unit: String,
                  val min: Double, val max: Double, val step: Double, val scale: Double = 1.0, val signed: Boolean = false)

    /** Rev limits are not tunable. Upshifts stay at least 500 rpm under the 6500 soft cut. */
    val tunables = listOf(
        Tunable(0, "TUNE_VTEC_A_ON", "VTEC on, map A", "rpm", 3000.0, 6000.0, 100.0),
        Tunable(1, "TUNE_VTEC_A_OFF", "VTEC off, map A", "rpm", 2800.0, 5800.0, 100.0),
        Tunable(2, "TUNE_VTEC_B_ON", "VTEC on, map B", "rpm", 3000.0, 6000.0, 100.0),
        Tunable(3, "TUNE_VTEC_B_OFF", "VTEC off, map B", "rpm", 2800.0, 5800.0, 100.0),
        Tunable(4, "TUNE_UP12", "Full throttle upshift 1-2", "rpm", 4500.0, 6000.0, 50.0),
        Tunable(5, "TUNE_UP23", "Full throttle upshift 2-3", "rpm", 4500.0, 6000.0, 50.0),
        Tunable(6, "TUNE_UP34", "Full throttle upshift 3-4", "rpm", 4500.0, 6000.0, 50.0),
        Tunable(7, "TUNE_UP45", "Full throttle upshift 4-5", "rpm", 4500.0, 6000.0, 50.0),
        Tunable(8, "TUNE_FAN_ON", "Fan on", "C", 88.0, 100.0, 0.5, 10.0),
        Tunable(9, "TUNE_FAN_OFF", "Fan off", "C", 85.0, 98.0, 0.5, 10.0),
        Tunable(10, "TUNE_IDLE", "Idle speed offset", "rpm", -100.0, 200.0, 25.0, 1.0, true),
    )

    /** Checks a new value against the limits and the related settings. Returns an error text, or null when fine. */
    fun check(t: Tunable, value: Double, current: (Int) -> Double?): String? {
        if (value < t.min - 1e-9 || value > t.max + 1e-9) return "${t.name}: allowed ${fmt(t.min)} to ${fmt(t.max)} ${t.unit}"
        fun other(id: Int) = current(id)
        when (t.id) {
            0, 2 -> other(t.id + 1)?.let { if (value < it + 100) return "VTEC on must stay at least 100 rpm above VTEC off (${fmt(it)})" }
            1, 3 -> other(t.id - 1)?.let { if (value > it - 100) return "VTEC off must stay at least 100 rpm below VTEC on (${fmt(it)})" }
            8 -> other(9)?.let { if (value < it + 2) return "Fan on must stay at least 2 C above fan off (${fmt(it)})" }
            9 -> other(8)?.let { if (value > it - 2) return "Fan off must stay at least 2 C below fan on (${fmt(it)})" }
        }
        return null
    }

    /** The 16-bit value sent for [value] (two's complement for signed settings). */
    fun raw(t: Tunable, value: Double): Int {
        val r = Math.round(value * t.scale).toInt()
        return r and 0xFFFF
    }

    /** Shown value from the 16-bit table word, or null for 0 (stock). */
    fun shown(t: Tunable, word: Int): Double? {
        if (word == 0) return null
        val r = if (t.signed && word >= 0x8000) word - 0x10000 else word
        return r / t.scale
    }

    fun fmt(v: Double): String = if (Math.abs(v - Math.rint(v)) < 1e-9) Math.round(v).toString() else String.format("%.1f", v)
}
