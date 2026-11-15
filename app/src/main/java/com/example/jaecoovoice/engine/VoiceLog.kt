package com.example.jaecoovoice.engine

import android.content.Context
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import java.util.UUID
import java.util.concurrent.Executors

/**
 * This app's logger: logcat as before, and warnings, errors and crashes also pushed to JaecooApp's log
 * sink (docs/log-sink-contract.md), which keeps and uploads them. Information (`i`) goes to the sink too,
 * where the server keeps it only as far as its budget allows.
 *
 * Does nothing beyond logcat until [init] runs (`JaecooVoiceApp.onCreate`).
 */
object VoiceLog {

    private const val AUTHORITY = "com.example.jaecooapp.logsink"
    private const val METHOD = "log"
    private const val CONTRACT = "1"
    private const val RUN_CHARS = 8

    @Volatile
    private var client: LogSinkClient? = null

    /** Starts the sink client and the crash handler, and reports how the previous run ended. Calling again does nothing. */
    @Synchronized
    fun init(context: Context, version: String, versionCode: Long) {
        if (client != null) return
        val app = context.applicationContext
        val current = LogRun(version, versionCode, UUID.randomUUID().toString().replace("-", "").take(RUN_CHARS))
        val previous = RunMarker.read(app.filesDir)
        RunMarker.write(app.filesDir, current)
        val created = LogSinkClient(
            send = { run, events -> push(app, run, events) },
            run = current,
            clock = System::currentTimeMillis,
            uptime = SystemClock::elapsedRealtime,
            executor = Executors.newSingleThreadExecutor { Thread(it, "voice-log").apply { isDaemon = true } }
        )
        client = created
        Thread.setDefaultUncaughtExceptionHandler(VoiceCrashHandler(Thread.getDefaultUncaughtExceptionHandler(), created::crash))
        i("AppStart", "process started")
        PreviousRun.report = CrashTrace.takeReport(app)?.also { created.log('E', "ProcessExit", "previous run: $it", context = deadRun(previous)) }
    }

    /** The context of the run that died, so its death is filed under it and not under the run reporting it. */
    private fun deadRun(previous: LogRun?): Map<String, Any?> = if (previous == null) {
        mapOf("run" to "unknown")
    } else {
        mapOf("run" to previous.run, "version" to previous.version, "versionCode" to previous.versionCode)
    }

    fun i(tag: String, msg: String) {
        logcat(Log.INFO, tag, msg, null)
        client?.log('I', tag, msg)
    }

    fun w(tag: String, msg: String, t: Throwable? = null) {
        logcat(Log.WARN, tag, msg, t)
        client?.log('W', tag, msg, t?.stackTraceToString())
    }

    fun e(tag: String, msg: String, t: Throwable? = null) {
        logcat(Log.ERROR, tag, msg, t)
        client?.log('E', tag, msg, t?.stackTraceToString())
    }

    /** One call to the sink; the number of events it took, or null when it cannot be reached (JaecooApp absent, too old, or gone). */
    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    private fun push(context: Context, run: LogRun, events: List<Map<String, Any?>>): Int? = try {
        val extras = Bundle().apply {
            putString("version", run.version)
            putLong("versionCode", run.versionCode)
            putString("run", run.run)
            putParcelableArrayList("events", ArrayList(events.map(::toBundle)))
        }
        context.contentResolver.acquireUnstableContentProviderClient(AUTHORITY)?.use { it.call(AUTHORITY, METHOD, CONTRACT, extras) }?.getInt("accepted")
    } catch (e: Exception) {
        null
    }

    private fun toBundle(event: Map<String, Any?>) = Bundle().apply {
        for ((key, value) in event) {
            when (value) {
                is String -> putString(key, value)
                is Long -> putLong(key, value)
                is Int -> putLong(key, value.toLong())
            }
        }
    }

    /** `android.util.Log` is a stub that throws in plain JVM unit tests; on a device it never throws. */
    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    private fun logcat(priority: Int, tag: String, msg: String, t: Throwable?) {
        try {
            Log.println(priority, tag, if (t != null) msg + '\n' + Log.getStackTraceString(t) else msg)
        } catch (e: RuntimeException) {
            // Not on a device.
        }
    }
}

/**
 * The report of how the previous run died, for the next `speak` to tell JaecooApp. [VoiceLog.init] reads it once
 * (reading clears the evidence) and logs it; the next speak hands it on once.
 */
object PreviousRun {
    @Volatile
    var report: String? = null

    @Synchronized
    fun take(): String? = report.also { report = null }
}

/** Gives the crash to [crash] (which writes it to the sink, synchronously), then to the handler that was installed before. */
class VoiceCrashHandler(
    private val previous: Thread.UncaughtExceptionHandler?,
    private val crash: (Thread, Throwable) -> Unit
) : Thread.UncaughtExceptionHandler {

    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    override fun uncaughtException(thread: Thread, throwable: Throwable) {
        try {
            crash(thread, throwable)
        } catch (t: Throwable) {
            // Whatever went wrong while writing, the crash must still reach the previous handler.
        }
        previous?.uncaughtException(thread, throwable)
    }
}
