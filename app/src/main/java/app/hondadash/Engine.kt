package app.hondadash

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Owns the adapter session on one worker thread. Connects, polls the values the current screen needs,
 * runs queued jobs (button commands, terminal lines, code read/clear) between polls, and feeds the logger.
 *
 * With auto reconnect on, it never gives up: when the car is off, the head unit sleeps or the adapter loses power,
 * it waits and tries again until the ECU answers. Only [stop] ends it.
 */
class Engine(private val events: (String) -> Unit) {
    enum class State { IDLE, CONNECTING, CONNECTED, WAITING, ERROR }

    @Volatile var state = State.IDLE
        private set
    @Volatile var linkLabel = ""
        private set
    val values = ConcurrentHashMap<String, Double>()
    val stamps = ConcurrentHashMap<String, Long>()

    /** Keys the screen wants. The worker reads this set each cycle. */
    @Volatile var wanted: Set<String> = emptySet()
    /** Keys of the screen read every cycle. The other screen keys are read a few per cycle in turn. Ignored while logging. */
    @Volatile var fast: Set<String> = emptySet()
    /** Keys read on every screen, so the background alarms and the trip computer work. */
    @Volatile var always: Set<String> = setOf("RPM_E", "ECT_F") + TRIP_KEYS
    /** Called on the worker thread after every poll cycle (trip computer, graph history). */
    @Volatile var onCycle: ((Long) -> Unit)? = null
    /** True when the screen is off: after a long wait the engine may then try less often to save battery. */
    @Volatile var allowSlow = false
    /** Extra keys always read while logging. */
    @Volatile var logKeys: Set<String> = emptySet()
    @Volatile var logger: CsvLogger? = null

    @Volatile var requestsPerSecond = 0.0
        private set
    @Volatile var cycleMs = 0L
        private set
    @Volatile var fastMode = true
        set(v) { field = v; elm?.fastMode = v }
    /** ECU file protocol byte (0 = v5.2 or older) and feature bits, see [Proto]. */
    @Volatile var ecuProto = 0
        private set
    @Volatile var ecuFeat = 0
        private set
    /** True while values come in through the multi-value snapshot (fast data). */
    @Volatile var snapshotActive = false
        private set
    @Volatile var valuesPerSecond = 0.0
        private set
    // snapshot slot programming, per connection (worker thread only)
    private var programmed: IntArray? = null
    private var programmedCount = -1
    private var snapOk = true
    private var snapFail = 0
    /** Slots must not change while the recorder is armed or recording. */
    @Volatile private var recFreeze = false

    /** Counts successful connections. The service compares it to play the connect sound once per connection. */
    @Volatile var connectCount = 0
        private set

    private val jobs = LinkedBlockingQueue<(Elm) -> Unit>()
    private val wake = LinkedBlockingQueue<Int>()
    private var worker: Thread? = null
    @Volatile private var running = false
    /** Bumped by every start and stop. A worker left over from an earlier start sees the change and quits quietly. */
    @Volatile private var gen = 0
    @Volatile private var elm: Elm? = null

    /**
     * Starts the session. [factory] makes a fresh link for every attempt.
     * With [retry] the engine keeps trying until [stop]; without it one failed attempt ends in ERROR.
     */
    @Synchronized
    fun start(label: String, retry: Boolean, factory: () -> Link) {
        stop()
        val my = ++gen
        running = true
        linkLabel = label
        wake.clear()
        worker = Thread({ supervise(my, factory, retry) }, "elm-worker").also { it.start() }
    }

    fun isRunning(): Boolean = running

    @Synchronized
    fun stop() {
        gen++
        running = false
        wake.offer(1)
        worker?.interrupt()
        try { elm?.close() } catch (_: Exception) {}
        try { worker?.join(1500) } catch (_: InterruptedException) {}
        worker = null
        elm = null
        state = State.IDLE
    }

    /** Ends the current wait between attempts, so the next attempt starts now. */
    fun retryNow() { wake.offer(1) }

    /** Queue work that needs the adapter (runs between polls). */
    fun post(job: (Elm) -> Unit) { jobs.offer(job) }

    private fun alive(my: Int): Boolean = running && gen == my

    private fun setState(my: Int, s: State) { if (gen == my) state = s }

    private fun supervise(my: Int, factory: () -> Link, retry: Boolean) {
        var delay = RETRY_MIN_MS
        var lastWaitReason = ""
        var waitingSince = 0L
        while (alive(my)) {
            setState(my, State.CONNECTING)
            var link: Link? = null
            val e: Elm
            try {
                link = factory()
                if (gen == my) linkLabel = link.label
                e = Elm(link) { events(it) }
                e.fastMode = fastMode
                e.open()
            } catch (ex: InterruptedException) {
                try { link?.close() } catch (_: Exception) {}
                break
            } catch (ex: Exception) {
                try { link?.close() } catch (_: Exception) {}
                if (!alive(my)) break
                val reason = ex.message ?: ex.javaClass.simpleName
                if (!retry) {
                    if (gen == my) { state = State.ERROR; running = false }
                    events("Connect failed: $reason")
                    return
                }
                setState(my, State.WAITING)
                val now = System.currentTimeMillis()
                if (waitingSince == 0L) waitingSince = now
                if (reason != lastWaitReason) { events("Waiting for the car: $reason"); lastWaitReason = reason }
                // after 10 minutes of waiting (car parked, phone still on) try less often to save battery
                val pause = if (allowSlow && now - waitingSince > LONG_WAIT_MS) RETRY_SLOW_MS else delay
                if (!sleep(my, pause)) break
                delay = Math.min((delay * 3) / 2, RETRY_MAX_MS)
                continue
            }
            if (!alive(my)) { try { e.close() } catch (_: Exception) {}; break }
            elm = e
            delay = RETRY_MIN_MS
            lastWaitReason = ""
            waitingSince = 0L
            connectCount++
            setState(my, State.CONNECTED)
            events("Connected: ${link.label}")
            val reason = poll(my, e)
            try { e.close() } catch (_: Exception) {}
            if (gen == my) elm = null
            if (!alive(my)) break
            if (!retry) {
                if (gen == my) { state = State.ERROR; running = false }
                events("Link lost: $reason")
                return
            }
            setState(my, State.WAITING)
            waitingSince = System.currentTimeMillis()
            events("Link lost: $reason. Reconnecting")
            if (!sleep(my, RETRY_MIN_MS)) break
        }
        if (gen == my && state != State.ERROR) state = State.IDLE
    }

    /** Waits [ms] or until [retryNow] / [stop]. Returns false when this worker should end. */
    private fun sleep(my: Int, ms: Long): Boolean = try {
        wake.poll(ms, TimeUnit.MILLISECONDS)
        alive(my)
    } catch (_: InterruptedException) { false }

    /** Polls until the link dies, the ECU goes silent or [stop]. Returns the reason. */
    private fun poll(my: Int, e: Elm): String {
        programmed = null; programmedCount = -1; snapOk = true; snapFail = 0; recFreeze = false
        snapshotActive = false
        var lastCaps = 0L
        var vcount = 0
        var count = 0
        var t0 = System.currentTimeMillis()
        var silent = 0
        var lastKeys: Set<String> = emptySet()
        var keysSince = 0L
        var slowIdx = 0
        try {
            while (alive(my)) {
                // jobs first: buttons must feel instant
                while (true) {
                    val j = jobs.poll() ?: break
                    try { j(e) } catch (ex: Exception) { events("Job error: ${ex.message}") }
                }
                if (System.currentTimeMillis() - lastCaps > CAPS_EVERY_MS) { lastCaps = System.currentTimeMillis(); readCaps(e); count++ }
                val logging = logger != null
                val keys = wanted + always + (if (logging) logKeys else emptySet())
                if (keys != lastKeys) { lastKeys = keys; keysSince = System.currentTimeMillis() }
                val groups = keys.mapNotNull { Pids[it] }.groupBy { it.request }
                if (groups.isEmpty()) {
                    val j = jobs.poll(200, TimeUnit.MILLISECONDS)
                    if (j != null) try { j(e) } catch (ex: Exception) { events("Job error: ${ex.message}") }
                    continue
                }
                val f = fast
                // fast data: as many mode 22 values as fit in one snapshot, the rest polled as before
                var snapPids: List<Pid> = emptyList()
                var snapSlots: IntArray? = null
                if (ecuFeat and Proto.F_SNAPSHOT != 0 && snapOk) {
                    val plan = planSnapshot(keys.mapNotNull { Pids[it] }, f)
                    if (programSlots(e, plan.first)) { snapSlots = programmed; snapPids = plan.second.filter { p -> Proto.slotsFor(p).all { it in programmed!! } } }
                }
                snapshotActive = snapSlots != null && snapPids.isNotEmpty()
                val covered = snapPids.map { it.key }.toSet()
                val groupsLeft = if (snapshotActive) groups.filterValues { g -> g.any { it.key !in covered } } else groups
                val tripG = groupsLeft.values.filter { g -> g.any { it.key in TRIP_KEYS } }
                val todo: List<List<Pid>> = if (logging || f.isEmpty()) {
                    // every value every cycle, with the trip values read again after every few others
                    val others = groupsLeft.values.filter { g -> g.none { it.key in TRIP_KEYS } }
                    val out = ArrayList<List<Pid>>(tripG)
                    for ((n, g) in others.withIndex()) {
                        out.add(g)
                        if ((n + 1) % TRIP_EVERY == 0 && n + 1 < others.size) out.addAll(tripG)
                    }
                    out
                } else {
                    val quick = groupsLeft.values.filter { g -> g.any { it.key in f || it.key in TRIP_KEYS } }
                    val slow = groupsLeft.entries.filter { en -> en.value.none { it.key in f || it.key in TRIP_KEYS } }.sortedBy { it.key }.map { it.value }
                    val pick = ArrayList<List<Pid>>()
                    for (k in 0 until Math.min(SLOW_PER_CYCLE, slow.size)) pick.add(slow[(slowIdx + k) % slow.size])
                    if (slow.isNotEmpty()) slowIdx = (slowIdx + SLOW_PER_CYCLE) % slow.size
                    quick + pick
                }
                val start = System.currentTimeMillis()
                if (snapshotActive) {
                    val slots = snapSlots!!
                    val frames = (slots.size + 1) / 2
                    val res = e.requestFrames("220100", frames)
                    count++
                    val now = System.currentTimeMillis()
                    if (res != null && (1..frames).all { it in res }) {
                        snapFail = 0
                        silent = 0
                        val m = HashMap<Int, IntArray>()
                        for ((i, a) in slots.withIndex()) {
                            val fr = res[i / 2 + 1]!!
                            val o = (i % 2) * 2
                            m[a] = intArrayOf(fr[o], fr[o + 1])
                        }
                        for (p in snapPids) {
                            val d = Proto.dataFor(p, m) ?: continue
                            values[p.key] = try { p.expr.eval(d) } catch (_: Exception) { Double.NaN }
                            stamps[p.key] = now
                            vcount++
                        }
                    } else {
                        if (++snapFail >= 3) { snapOk = false; snapshotActive = false; events("Fast data off: the ECU did not answer the snapshot") }
                        if (++silent >= SILENT_LIMIT) return "ECU not answering (ignition off?)"
                    }
                }
                for (group in todo) {
                    if (!alive(my)) break
                    if (jobs.isNotEmpty()) {
                        val j = jobs.poll()
                        if (j != null) try { j(e) } catch (ex: Exception) { events("Job error: ${ex.message}") }
                    }
                    val data = e.request(group[0].request, group[0].echoBytes)
                    count++
                    val now = System.currentTimeMillis()
                    if (data != null) {
                        silent = 0
                        vcount += group.size
                        for (p in group) {
                            val v = try { p.expr.eval(data) } catch (_: Exception) { Double.NaN }
                            values[p.key] = v
                            stamps[p.key] = now
                        }
                    } else if (++silent >= SILENT_LIMIT) {
                        return "ECU not answering (ignition off?)"
                    }
                }
                // values read since this screen opened feed the derived values (a value left from another screen is stale)
                val live = HashSet<String>()
                for (k in keys) { val st = stamps[k]; if (st != null && st >= keysSince) live.add(k) }
                Derived.compute(values, live)
                values[Derived.RATE] = requestsPerSecond
                cycleMs = System.currentTimeMillis() - start
                try { onCycle?.invoke(System.currentTimeMillis()) } catch (_: Exception) {}
                logger?.row(values)
                val now = System.currentTimeMillis()
                if (now - t0 >= 2000) {
                    requestsPerSecond = count * 1000.0 / (now - t0)
                    valuesPerSecond = vcount * 1000.0 / (now - t0)
                    count = 0
                    vcount = 0
                    t0 = now
                }
            }
            return "stopped"
        } catch (ex: InterruptedException) {
            return "stopped"
        } catch (ex: Exception) {
            return ex.message ?: ex.javaClass.simpleName
        }
    }

    /** Reads the ECU file protocol byte and feature bits (both 0 on v5.2 and older). */
    private fun readCaps(e: Elm) {
        val d = e.request("226039", 2) ?: return
        val proto = d[0]
        val feat = if (proto in 0x53..0xEF) d[1] else 0
        if (proto != ecuProto || feat != ecuFeat) {
            ecuProto = proto; ecuFeat = feat
            if (proto in 0x53..0xEF) events(String.format("ECU file protocol %02X, features %02X", proto, feat))
        }
    }

    /** Slots for the snapshot: trip and fast values first, then the rest, up to [Proto.MAX_SLOTS]. */
    private fun planSnapshot(pids: List<Pid>, f: Set<String>): Pair<IntArray, List<Pid>> {
        val p22 = pids.filter { Proto.address(it.request) >= 0 }
            .sortedBy { if (it.key in TRIP_KEYS || it.key in f) 0 else 1 }
        val slots = LinkedHashSet<Int>()
        val inSnap = ArrayList<Pid>()
        for (p in p22) {
            val need = Proto.slotsFor(p).filter { it !in slots }
            if (slots.size + need.size > Proto.MAX_SLOTS) continue
            slots.addAll(need)
            inSnap.add(p)
        }
        return slots.toIntArray() to inSnap
    }

    /**
     * Brings the ECU slot table to [target], a few commands per cycle so polling goes on meanwhile.
     * Returns true when the table matches and the snapshot can be used. While the recorder runs the table is kept.
     */
    private fun programSlots(e: Elm, target: IntArray): Boolean {
        if (recFreeze) return programmed != null && programmedCount == programmed!!.size
        val have = programmed
        if (have != null && have.contentEquals(target) && programmedCount == target.size) return true
        if (target.isEmpty()) return false
        var first = 0
        if (have != null) while (first < target.size && first < have.size && have[first] == target[first]) first++
        val next = (have ?: IntArray(0)).copyOf(Math.max(target.size, have?.size ?: 0))
        var sent = 0
        if (first < target.size) {
            if (!e.command(String.format("2311%02X", first))) return progFail()
            var i = first
            while (i < target.size && sent < PROG_PER_CYCLE) {
                if (!e.command(String.format("2312%02X", target[i] shr 8)) || !e.command(String.format("2313%02X", target[i] and 0xFF))) return progFail()
                next[i] = target[i]
                i++; sent += 2
            }
            programmed = next.copyOf(Math.max(i, have?.size ?: 0))
            programmedCount = -1
            if (i < target.size) return false
        }
        programmed = target.copyOf()
        if (!e.command(String.format("2310%02X", target.size))) return progFail()
        programmedCount = target.size
        return true
    }

    private fun progFail(): Boolean {
        snapOk = false
        programmed = null
        events("Fast data off: the ECU did not accept the value list")
        return false
    }

    /** Arms the 10 ms recorder. [mode]: 1 record now, 2 at full throttle, 3 at lift-off. */
    fun recorderArm(mode: Int, done: (Boolean) -> Unit) = post { e ->
        recFreeze = true
        val s = Proto.recorderSlots
        var ok = e.command("231100")
        for (a in s) if (ok) ok = e.command(String.format("2312%02X", a shr 8)) && e.command(String.format("2313%02X", a and 0xFF))
        if (ok) ok = e.command(String.format("2310%02X", s.size))
        if (ok) { programmed = s.toIntArray(); programmedCount = s.size }
        if (ok) ok = e.command(String.format("2320%02X", mode))
        if (!ok) { recFreeze = false; programmed = null }
        done(ok)
    }

    /** Stops and clears the recorder; the snapshot may change its value list again. */
    fun recorderStop() = post { e -> e.command("232000"); recFreeze = false }

    /**
     * Downloads the recording into a CSV in [dir]. Calls [done] with the file, or null and the reason.
     * The values are the ones in [Proto.recorderSlots], one row every 10 ms.
     */
    fun recorderDownload(dir: java.io.File, done: (java.io.File?, String) -> Unit) = post { e ->
        val st = e.request("226039", 2)
        val cn = e.request("22603D", 2)
        if (st == null || cn == null) { done(null, "no answer from the ECU"); return@post }
        val k = st[3]
        val n = (cn[0] shl 8) or cn[1]
        if (st[2] != 3 || k <= 0 || n <= 0) { done(null, "nothing recorded yet (state ${st[2]})"); return@post }
        val total = n * k * 2
        val blocks = (total + 59) / 60
        val buf = ByteArray(blocks * 60)
        for (b in 0 until blocks) {
            var res = e.requestFrames(String.format("2202%02X", b), 15)
            if (res == null || res.size < 15) res = e.requestFrames(String.format("2202%02X", b), 15)
            if (res == null || (1..15).any { it !in res }) { done(null, "block $b of $blocks did not arrive"); return@post }
            for (f in 1..15) for (i in 0 until 4) buf[b * 60 + (f - 1) * 4 + i] = res[f]!![i].toByte()
        }
        val slots = Proto.recorderSlots.take(k)
        val pids = PidTable.all.filter { p -> Proto.address(p.request) >= 0 && Proto.slotsFor(p).all { it in slots } }
        val stamp = java.text.SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", java.util.Locale.US).format(java.util.Date())
        val file = java.io.File(dir, "HondaDash_${stamp}_Recorder10ms.csv")
        file.bufferedWriter().use { w ->
            w.write("Time(ms)," + pids.joinToString(",") { p -> (if (p.unit.isNotEmpty()) "${p.name}(${p.unit})" else p.name).replace(',', ' ') } + "\n")
            for (i in 0 until n) {
                val m = HashMap<Int, IntArray>()
                for ((j, a) in slots.withIndex()) {
                    val o = (i * k + j) * 2
                    m[a] = intArrayOf(buf[o].toInt() and 0xFF, buf[o + 1].toInt() and 0xFF)
                }
                val sb = StringBuilder().append(i * Proto.REC_SAMPLE_MS)
                for (p in pids) {
                    sb.append(',')
                    val d = Proto.dataFor(p, m)
                    if (d != null) { val v = try { p.expr.eval(d) } catch (_: Exception) { Double.NaN }; if (!v.isNaN()) sb.append(String.format(java.util.Locale.US, "%.2f", v)) }
                }
                w.write(sb.append('\n').toString())
            }
        }
        e.command("232000")
        recFreeze = false
        done(file, "$n samples, ${n * Proto.REC_SAMPLE_MS / 1000.0} s")
    }

    /** Live tuning: sets setting [t] to [value], or back to stock when null. */
    fun tune(t: Proto.Tunable, value: Double?, done: (Boolean) -> Unit) = post { e ->
        val raw = if (value == null) 0 else Proto.raw(t, value)
        done(e.command(String.format("2330%02X", t.id)) && e.command(String.format("2331%02X", raw shr 8)) &&
            e.command(String.format("2332%02X", raw and 0xFF)))
    }

    companion object {
        const val CAPS_EVERY_MS = 20_000L
        /** Slot programming commands per poll cycle (so values keep coming while the list changes). */
        const val PROG_PER_CYCLE = 8
        const val RETRY_MIN_MS = 1000L
        const val RETRY_MAX_MS = 5000L
        const val RETRY_SLOW_MS = 30000L
        const val LONG_WAIT_MS = 10 * 60 * 1000L
        /** Unanswered requests in a row before the session is restarted (a new K-line wake-up). */
        const val SILENT_LIMIT = 10
        /** Screen values read per cycle besides the fast ones, when not logging. */
        const val SLOW_PER_CYCLE = 2
        /** Read on every cycle on every screen: rpm, speed, fuel per injection and the decel fuel cut flag. */
        val TRIP_KEYS = setOf("RPM_E", "SPEED", "FUEL_EVT", "DFCO")
        /** In full polling, the trip values are read again after this many other requests. */
        const val TRIP_EVERY = 6
    }
}
