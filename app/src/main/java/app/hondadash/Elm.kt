package app.hondadash

/**
 * ELM327 session on the K-line (ISO 9141-2). All calls must come from one worker thread.
 * Requests are hex strings like "22D742". Replies are parsed with headers off and spaces off.
 */
class Elm(private val link: Link, private val log: (String) -> Unit) {
    /** Append the ELM "one response expected" digit, so the adapter returns as soon as the ECU answers. */
    @Volatile var fastMode = true
    private var fastFailures = 0
    var version = ""
        private set

    fun open() {
        link.open()
        version = cmd("ATZ", 3000).trim()
        log("Adapter: $version")
        for (c in listOf("ATE0", "ATL0", "ATS0", "ATH0", "ATSP3", "ATAT2")) cmd(c, 1500)
        // First OBD request starts the 5-baud K-line wake-up. It can take several seconds.
        val r = cmd("0100", 9000)
        log("Init 0100: ${clean(r)}")
        if (!clean(r).contains("4100")) {
            val r2 = cmd("0100", 7000)
            log("Init retry: ${clean(r2)}")
            // no answer twice: the ECU is off or asleep, so the caller waits and tries again
            if (!clean(r2).contains("4100")) throw java.io.IOException("ECU not answering (ignition off?)")
        }
    }

    fun close() = link.close()

    /** Raw command for the terminal screen. */
    fun raw(c: String, timeoutMs: Long = 4000): String = cmd(c, timeoutMs)

    private fun cmd(c: String, timeoutMs: Long): String {
        link.write(c)
        return link.readUntilPrompt(timeoutMs)
    }

    private fun clean(r: String): String = r.uppercase()
        .replace("SEARCHING...", "")
        .replace("BUS INIT: ...OK", "")
        .replace("BUS INIT: ...", "")
        .replace("\r", "").replace("\n", "").replace(" ", "")
        .replace("\u0000TIMEOUT", "")

    /**
     * Sends a request and returns the data bytes after the echo, or null on no answer.
     * [echo] is how many request bytes after the mode byte the ECU repeats (2 for mode 22, 1 for mode 01).
     */
    fun request(req: String, echo: Int, timeoutMs: Long = 1500): IntArray? {
        val useFast = fastMode
        val sent = if (useFast) req + "1" else req
        val text = clean(cmd(sent, timeoutMs))
        val expect = String.format("%02X", req.substring(0, 2).toInt(16) + 0x40) + req.substring(2, 2 + echo * 2)
        val at = text.indexOf(expect)
        if (at < 0) {
            if (useFast) {
                fastFailures++
                if (fastFailures >= 6) {
                    fastMode = false
                    log("Fast mode off: adapter did not accept the response-count digit")
                }
            }
            return null
        }
        if (useFast) fastFailures = 0
        val hex = text.substring(at + expect.length)
        val n = hex.length / 2
        val out = IntArray(n)
        for (k in 0 until n) {
            val b = hex.substring(2 * k, 2 * k + 2).toIntOrNull(16) ?: return null
            out[k] = b
        }
        return out
    }

    /**
     * Request answered with several frames ("62 01 kk d0 d1 d2 d3" per frame, like a VIN read).
     * Returns frame number -> 4 data bytes, or null when nothing came back.
     */
    fun requestFrames(req: String, frames: Int, timeoutMs: Long = 3000): Map<Int, IntArray>? {
        val sent = if (fastMode && frames in 1..15) req + Integer.toHexString(frames).uppercase() else req
        val raw = cmd(sent, timeoutMs)
        val head = "62" + req.substring(2, 4)
        val out = HashMap<Int, IntArray>()
        for (line in raw.split('\r', '\n')) {
            val t = clean(line)
            val at = t.indexOf(head)
            if (at < 0) continue
            val body = t.substring(at + 4)
            if (body.length < 10) continue
            val k = body.substring(0, 2).toIntOrNull(16) ?: continue
            val d = IntArray(4)
            var ok = true
            for (i in 0 until 4) {
                val b = body.substring(2 + 2 * i, 4 + 2 * i).toIntOrNull(16)
                if (b == null) { ok = false; break }
                d[i] = b
            }
            if (ok) out[k] = d
        }
        return if (out.isEmpty()) null else out
    }

    /** Mode 23 command to the v5.x handler. Returns true when the ECU confirmed it. */
    fun command(code: String): Boolean {
        val text = clean(cmd(code, 2000))
        return text.contains("63" + code.substring(2, 4))
    }

    /** Stored trouble codes (mode 03). ISO 9141 sends up to 3 codes per frame, one frame per line. */
    fun readCodes(): List<String> {
        val raw = cmd("03", 5000)
        val out = ArrayList<String>()
        for (line in raw.split('\r', '\n')) {
            val t = clean(line)
            if (!t.startsWith("43")) continue
            var j = 2
            while (j + 4 <= t.length) {
                val w = t.substring(j, j + 4).toIntOrNull(16) ?: break
                if (w != 0) out.add(dtc(w))
                j += 4
            }
        }
        return out.distinct()
    }

    fun clearCodes(): Boolean = clean(cmd("04", 5000)).contains("44")

    private fun dtc(w: Int): String {
        val letter = "PCBU"[(w shr 14) and 3]
        return letter + String.format("%04X", w and 0x3FFF)
    }
}
