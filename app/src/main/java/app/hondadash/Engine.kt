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
    /** Keys read on every screen, so the background alarms work. */
    @Volatile var always: Set<String> = setOf("RPM_E", "ECT_F")
    /** Extra keys always read while logging. */
    @Volatile var logKeys: Set<String> = emptySet()
    @Volatile var logger: CsvLogger? = null

    @Volatile var requestsPerSecond = 0.0
        private set
    @Volatile var cycleMs = 0L
        private set
    @Volatile var fastMode = true
        set(v) { field = v; elm?.fastMode = v }
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
                val pause = if (now - waitingSince > LONG_WAIT_MS) RETRY_SLOW_MS else delay
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
                val todo: List<List<Pid>> = if (logging || f.isEmpty()) groups.values.toList() else {
                    val quick = groups.values.filter { g -> g.any { it.key in f } }
                    val slow = groups.entries.filter { en -> en.value.none { it.key in f } }.sortedBy { it.key }.map { it.value }
                    val pick = ArrayList<List<Pid>>()
                    for (k in 0 until Math.min(SLOW_PER_CYCLE, slow.size)) pick.add(slow[(slowIdx + k) % slow.size])
                    if (slow.isNotEmpty()) slowIdx = (slowIdx + SLOW_PER_CYCLE) % slow.size
                    quick + pick
                }
                val start = System.currentTimeMillis()
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
                logger?.row(values)
                val now = System.currentTimeMillis()
                if (now - t0 >= 2000) {
                    requestsPerSecond = count * 1000.0 / (now - t0)
                    count = 0
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

    companion object {
        const val RETRY_MIN_MS = 2000L
        const val RETRY_MAX_MS = 8000L
        const val RETRY_SLOW_MS = 30000L
        const val LONG_WAIT_MS = 10 * 60 * 1000L
        /** Unanswered requests in a row before the session is restarted (a new K-line wake-up). */
        const val SILENT_LIMIT = 10
        /** Screen values read per cycle besides the fast ones, when not logging. */
        const val SLOW_PER_CYCLE = 2
    }
}
