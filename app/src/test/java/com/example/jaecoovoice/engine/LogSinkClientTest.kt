package com.example.jaecoovoice.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LogSinkClientTest {

    private class Sent(val version: String, val versionCode: Long, val run: String, val events: List<Map<String, Any?>>)

    private val context = LogRun("1.6.0", 16L, "ab12cd34")

    private val sent = mutableListOf<Sent>()
    private val executor = ManualExecutor()
    private var reachable = true
    private var now = 10L
    private var up = 20L

    private fun client(): LogSinkClient = LogSinkClient(
        send = { run, events ->
            if (reachable) {
                sent.add(Sent(run.version, run.versionCode, run.run, events))
                events.size
            } else {
                null
            }
        },
        run = context,
        clock = { now },
        uptime = { up },
        executor = executor
    )

    private fun List<Sent>.messages() = flatMap { s -> s.events.map { it["msg"] as String } }

    @Test
    fun batchesUpToTwenty() {
        val c = client()
        repeat(25) { c.log('W', "T", "m$it") }
        executor.runAll()
        assertEquals(listOf(20, 5), sent.map { it.events.size })
        assertEquals((0 until 25).map { "m$it" }, sent.messages())
    }

    @Test
    fun keepsEventsWhenTheSinkIsAbsent() {
        val c = client()
        reachable = false
        c.log('W', "T", "first")
        executor.runAll()
        assertTrue(sent.isEmpty())
        reachable = true
        c.log('W', "T", "second")
        executor.runAll()
        assertEquals(listOf("first", "second"), sent.messages())
    }

    @Test
    fun anAbsentSinkNeverLogsAboutItself() {
        val c = client()
        reachable = false
        repeat(3) { c.log('W', "T", "m") }
        executor.runAll()
        reachable = true
        c.log('W', "T", "after")
        executor.runAll()
        assertTrue(sent.messages().none { it.contains("LogSink", ignoreCase = true) })
    }

    @Test
    fun dropsOldestOverTwoHundredAndSaysSo() {
        val c = client()
        reachable = false
        repeat(205) { c.log('W', "T", "m$it") }
        executor.runAll()
        reachable = true
        c.log('W', "T", "now")
        executor.runAll()
        val first = sent.first().events.first()
        assertEquals("W", first["level"])
        assertEquals("LogSink", first["tag"])
        assertEquals("6 lines dropped", first["msg"])
        val all = sent.messages()
        assertTrue(all.contains("now"))
        assertTrue("oldest are gone", !all.contains("m0") && all.contains("m10"))
    }

    @Test
    fun crashFlushesSynchronouslyAndLastEventIsFatalWithStack() {
        val c = client()
        reachable = false
        c.log('W', "T", "before")
        executor.runAll()
        reachable = true
        c.crash(Thread.currentThread(), IllegalStateException("boom"))
        assertEquals(0, executor.queued)
        val events = sent.flatMap { it.events }
        assertEquals(listOf("before"), events.dropLast(1).map { it["msg"] })
        val fatal = events.last()
        assertEquals("F", fatal["level"])
        assertEquals("Crash", fatal["tag"])
        assertTrue((fatal["stack"] as String).contains("LogSinkClientTest"))
    }

    @Test
    fun eventsCarryTsUptimeAndTheRunContext() {
        val c = client()
        c.log('E', "T", "m", stack = "stk")
        executor.runAll()
        val batch = sent.single()
        assertEquals(Triple("1.6.0", 16L, "ab12cd34"), Triple(batch.version, batch.versionCode, batch.run))
        val e = batch.events.single()
        assertEquals(10L, e["ts"])
        assertEquals(20L, e["up"])
        assertEquals("E", e["level"])
        assertEquals("stk", e["stack"])
    }

    @Test
    fun anEventCanCarryTheContextOfAnEarlierRun() {
        val c = client()
        c.log('E', "ProcessExit", "died", context = mapOf("run" to "old1", "version" to "1.5.0", "versionCode" to 15L))
        executor.runAll()
        val e = sent.single().events.single()
        assertEquals("old1", e["run"])
        assertEquals("1.5.0", e["version"])
        assertEquals(15L, e["versionCode"])
    }

    @Test
    fun aSendThatThrowsIsTreatedAsUnreachable() {
        val c = LogSinkClient({ _, _ -> error("binder is gone") }, LogRun("1", 1L, "r"), { 1L }, { 1L }, executor)
        c.log('W', "T", "m")
        executor.runAll()
        c.crash(Thread.currentThread(), RuntimeException("x"))
    }
}
