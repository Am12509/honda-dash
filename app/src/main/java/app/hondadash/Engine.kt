package app.hondadash

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Owns the adapter session on one worker thread. Polls the values the current screen needs,
 * runs queued jobs (button commands, terminal lines, code read/clear) between polls, and feeds the logger.
 */
class Engine(private val events: (String) -> Unit) {
    enum class State { IDLE, CONNECTING, CONNECTED, ERROR }

    @Volatile var state = State.IDLE
        private set
    @Volatile var linkLabel = ""
        private set
    val values = ConcurrentHashMap<String, Double>()
    val stamps = ConcurrentHashMap<String, Long>()

    /** Keys the screen wants. The worker reads this set each cycle. */
    @Volatile var wanted: Set<String> = emptySet()
    /** Extra keys always read while logging. */
    @Volatile var logKeys: Set<String> = emptySet()
    @Volatile var logger: CsvLogger? = null

    @Volatile var requestsPerSecond = 0.0
        private set
    @Volatile var cycleMs = 0L
        private set
    @Volatile var fastMode = true
        set(v) { field = v; elm?.fastMode = v }

    private val jobs = LinkedBlockingQueue<(Elm) -> Unit>()
    private var worker: Thread? = null
    @Volatile private var running = false
    private var elm: Elm? = null

    fun start(link: Link) {
        stop()
        running = true
        linkLabel = link.label
        worker = Thread({ loop(link) }, "elm-worker").also { it.start() }
    }

    fun stop() {
        running = false
        worker?.interrupt()
        try { worker?.join(1500) } catch (_: InterruptedException) {}
        worker = null
        try { elm?.close() } catch (_: Exception) {}
        elm = null
        if (state != State.ERROR) state = State.IDLE
    }

    /** Queue work that needs the adapter (runs between polls). */
    fun post(job: (Elm) -> Unit) { jobs.offer(job) }

    private fun loop(link: Link) {
        state = State.CONNECTING
        val e = Elm(link) { events(it) }
        e.fastMode = fastMode
        try {
            e.open()
            elm = e
            state = State.CONNECTED
            events("Connected: ${link.label}")
        } catch (ex: Exception) {
            state = State.ERROR
            events("Connect failed: ${ex.message}")
            try { link.close() } catch (_: Exception) {}
            return
        }
        var count = 0
        var t0 = System.currentTimeMillis()
        try {
            while (running) {
                // jobs first: buttons must feel instant
                while (true) {
                    val j = jobs.poll() ?: break
                    try { j(e) } catch (ex: Exception) { events("Job error: ${ex.message}") }
                }
                val keys = wanted + (if (logger != null) logKeys else emptySet())
                val pids = keys.mapNotNull { Pids[it] }
                if (pids.isEmpty()) {
                    val j = jobs.poll(200, TimeUnit.MILLISECONDS)
                    if (j != null) try { j(e) } catch (ex: Exception) { events("Job error: ${ex.message}") }
                    continue
                }
                val start = System.currentTimeMillis()
                // one request per unique address, shared by every value read from it
                for ((req, group) in pids.groupBy { it.request }) {
                    if (!running) break
                    if (jobs.isNotEmpty()) {
                        val j = jobs.poll()
                        if (j != null) try { j(e) } catch (ex: Exception) { events("Job error: ${ex.message}") }
                    }
                    val data = e.request(req, group[0].echoBytes)
                    count++
                    val now = System.currentTimeMillis()
                    if (data != null) {
                        for (p in group) {
                            val v = try { p.expr.eval(data) } catch (_: Exception) { Double.NaN }
                            values[p.key] = v
                            stamps[p.key] = now
                        }
                    }
                }
                // only values read in this cycle feed the derived values (a value left from another screen is stale)
                val live = HashSet<String>()
                for (p in pids) { val st = stamps[p.key]; if (st != null && st >= start) live.add(p.key) }
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
        } catch (ex: InterruptedException) {
            // stopping
        } catch (ex: Exception) {
            state = State.ERROR
            events("Link lost: ${ex.message}")
        } finally {
            try { e.close() } catch (_: Exception) {}
            elm = null
            if (state == State.CONNECTED) state = State.IDLE
        }
    }
}
