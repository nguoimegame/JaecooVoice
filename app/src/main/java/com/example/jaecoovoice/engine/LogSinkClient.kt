package com.example.jaecoovoice.engine

import java.util.concurrent.Executor

/** Which build and which run of this process the events belong to. */
class LogRun(val version: String, val versionCode: Long, val run: String)

/**
 * Pushes this app's warnings, errors and crashes to JaecooApp's log sink (docs/log-sink-contract.md).
 * Nothing is kept on disk and nothing reaches the network from here: JaecooApp redacts, queues and
 * uploads them with its own.
 *
 * Events wait in memory (at most [MAX_QUEUED]; the oldest go first and the loss is reported once
 * delivery works again) and go out in batches of at most [MAX_BATCH] on [executor]. When the sink cannot be
 * reached, the events stay and the next call to [log] tries again; it never logs about that, which
 * would only feed itself.
 *
 * [send] makes one call to the sink: the number of events it took, or null when it could not be reached.
 */
class LogSinkClient(
    private val send: (run: LogRun, events: List<Map<String, Any?>>) -> Int?,
    private val run: LogRun,
    private val clock: () -> Long,
    private val uptime: () -> Long,
    private val executor: Executor
) {

    private class Batch(val events: List<Map<String, Any?>>, val queued: Int, val dropped: Int)

    private val lock = Any()
    private val sendLock = Any()
    private val queue = ArrayDeque<Map<String, Any?>>()
    private var dropped = 0
    private var scheduled = false

    /** [context] may carry `run`, `version` and `versionCode` of an earlier run, for an event that is about it. */
    fun log(level: Char, tag: String, msg: String, stack: String? = null, context: Map<String, Any?> = emptyMap()) {
        val schedule = synchronized(lock) {
            enqueue(event(level, tag, msg, stack, context))
            !scheduled.also { scheduled = true }
        }
        if (schedule) executor.execute(::flush)
    }

    /** Writes the crash and everything waiting on the calling thread, because the process is about to die. */
    fun crash(thread: Thread, t: Throwable) {
        synchronized(lock) {
            enqueue(event('F', "Crash", "${t.javaClass.name}: ${t.message} (thread ${thread.name})", t.stackTraceToString(), emptyMap()))
        }
        flush()
    }

    private fun event(level: Char, tag: String, msg: String, stack: String?, context: Map<String, Any?>): Map<String, Any?> {
        val e = mutableMapOf<String, Any?>("ts" to clock(), "up" to uptime(), "level" to level.toString(), "tag" to tag, "msg" to msg)
        if (stack != null) e["stack"] = stack
        for (key in CONTEXT_KEYS) context[key]?.let { e[key] = it }
        return e
    }

    private fun enqueue(event: Map<String, Any?>) {
        queue.addLast(event)
        while (queue.size > MAX_QUEUED) {
            queue.removeFirst()
            dropped++
        }
    }

    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    private fun flush() {
        synchronized(sendLock) {
            synchronized(lock) { scheduled = false }
            while (true) {
                val batch = synchronized(lock) { take() } ?: return
                val delivered = try {
                    send(run, batch.events) != null
                } catch (e: Exception) {
                    // A dead binder or a refusing provider is "unreachable"; the events wait for the next call.
                    false
                }
                if (!delivered) {
                    synchronized(lock) { putBack(batch) }
                    return
                }
            }
        }
    }

    /** The next batch, with a line saying how many events were lost first; null when there is nothing to send. */
    private fun take(): Batch? {
        if (queue.isEmpty() && dropped == 0) return null
        val events = mutableListOf<Map<String, Any?>>()
        val lost = dropped
        if (lost > 0) {
            events.add(event('W', "LogSink", "$lost lines dropped", null, emptyMap()))
            dropped = 0
        }
        var queued = 0
        while (queue.isNotEmpty() && events.size < MAX_BATCH) {
            events.add(queue.removeFirst())
            queued++
        }
        return Batch(events, queued, lost)
    }

    private fun putBack(batch: Batch) {
        val real = batch.events.takeLast(batch.queued)
        real.asReversed().forEach { queue.addFirst(it) }
        dropped += batch.dropped
        while (queue.size > MAX_QUEUED) {
            queue.removeFirst()
            dropped++
        }
    }

    private companion object {
        const val MAX_QUEUED = 200
        const val MAX_BATCH = 20
        val CONTEXT_KEYS = listOf("run", "version", "versionCode")
    }
}
