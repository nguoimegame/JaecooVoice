package com.example.jaecoovoice.engine

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import android.os.Process
import android.os.SystemClock
import androidx.annotation.RequiresApi
import java.io.File

/**
 * Finds out why this process died. A native crash in sherpa-onnx or espeak-ng leaves no Java stack
 * and JaecooApp only sees "binder died", so the TTS code leaves a breadcrumb file before every
 * risky step ([mark]) and the next speak reports what it says plus Android's own exit record
 * ([takeReport]).
 */
object CrashTrace {
    private const val TRACE_FILE = "tts-trace.txt"
    private const val REPORTED_FILE = "tts-trace-reported.txt"
    const val DONE = "done"

    /** Records the step about to run; [DONE] means nothing is in flight. Never throws. */
    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    fun mark(filesDir: File, step: String) {
        try {
            File(filesDir, TRACE_FILE).writeText("$step\n${System.currentTimeMillis()}")
        } catch (e: Exception) {
            // A missing breadcrumb must not break speech.
        }
    }

    /**
     * What killed the previous process, once per death; null when it ended normally.
     * Safe to call from any thread.
     */
    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    fun takeReport(context: Context): String? = try {
        val dir = context.filesDir
        val now = System.currentTimeMillis()
        val interrupted = interruption(dir, processStartMs(now), now)
        val exit = lastExit(context, File(dir, REPORTED_FILE))
        when {
            exit == null && interrupted == null -> null
            else -> buildString {
                append("previous run died")
                if (interrupted != null) append(" $interrupted")
                if (exit != null) append("; exit: $exit")
            }
        }
    } catch (e: Exception) {
        null
    }

    /**
     * The step an earlier process left unfinished, as "during '<step>' (Ns before)", and clears
     * it; null when there is none. A step marked after [processStartMs] belongs to this process
     * (a warmUp still loading on the TTS thread), so it is no death and is left alone.
     */
    internal fun interruption(filesDir: File, processStartMs: Long, nowMs: Long): String? {
        val trace = File(filesDir, TRACE_FILE).takeIf { it.isFile }?.readText()?.lines().orEmpty()
        val step = trace.getOrNull(0).orEmpty()
        val stepAt = trace.getOrNull(1)?.toLongOrNull() ?: 0L
        if (step.isEmpty() || step == DONE || stepAt >= processStartMs) return null
        mark(filesDir, DONE)
        return "during '$step' (${(nowMs - stepAt) / MS_PER_S}s before)"
    }

    /** Wall-clock time this process started, comparable with the breadcrumb's timestamp. */
    private fun processStartMs(nowMs: Long): Long = nowMs - (SystemClock.elapsedRealtime() - Process.getStartElapsedRealtime())

    private const val MS_PER_S = 1000L

    private fun lastExit(context: Context, reported: File): String? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        lastExitApi30(context, reported)
    } else {
        null
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun lastExitApi30(context: Context, reported: File): String? {
        val manager = context.getSystemService(ActivityManager::class.java)
        val seen = reported.takeIf { it.isFile }?.readText()?.trim()?.toLongOrNull() ?: 0L
        val info = manager?.getHistoricalProcessExitReasons(context.packageName, 0, 1)
            ?.firstOrNull { it.timestamp > seen }
        info?.let { reported.writeText(it.timestamp.toString()) }
        return info?.takeIf { it.reason !in BENIGN }?.let {
            "reason=${reasonName(it.reason)} status=${it.status} rssKb=${it.rss / KB} " +
                "importance=${it.importance} desc=${it.description.orEmpty()}" + tombstoneText(it)
        }
    }

    /**
     * The readable strings of a native crash's tombstone (abort message, library and function
     * names), which place a crash that has no Java stack. Empty when there is none.
     */
    @RequiresApi(Build.VERSION_CODES.R)
    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    private fun tombstoneText(info: ApplicationExitInfo): String = try {
        val bytes = if (info.reason == ApplicationExitInfo.REASON_CRASH_NATIVE) {
            info.traceInputStream?.use { it.readBytes() }
        } else {
            null
        }
        val words = bytes?.let { String(it, Charsets.ISO_8859_1) }
            ?.let { PRINTABLE.findAll(it).map { m -> m.value }.distinct().toList() }
            .orEmpty()
        if (words.isEmpty()) "" else "; tombstone: " + words.joinToString(" | ").take(MAX_TOMBSTONE)
    } catch (e: Exception) {
        "; tombstone unreadable: ${e.javaClass.simpleName}"
    }

    private const val KB = 1024L
    private const val MAX_TOMBSTONE = 1800
    private val PRINTABLE = Regex("[ -~]{8,}")

    /** Exits somebody asked for: not worth a report. */
    private val BENIGN = setOf(
        ApplicationExitInfo.REASON_USER_REQUESTED,
        ApplicationExitInfo.REASON_USER_STOPPED,
        ApplicationExitInfo.REASON_PERMISSION_CHANGE,
        ApplicationExitInfo.REASON_EXIT_SELF
    )

    private fun reasonName(reason: Int) = when (reason) {
        ApplicationExitInfo.REASON_CRASH -> "CRASH"
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "CRASH_NATIVE"
        ApplicationExitInfo.REASON_SIGNALED -> "SIGNALED"
        ApplicationExitInfo.REASON_LOW_MEMORY -> "LOW_MEMORY"
        ApplicationExitInfo.REASON_ANR -> "ANR"
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "EXCESSIVE_RESOURCE_USAGE"
        ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "INITIALIZATION_FAILURE"
        ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "DEPENDENCY_DIED"
        ApplicationExitInfo.REASON_USER_REQUESTED -> "USER_REQUESTED"
        ApplicationExitInfo.REASON_USER_STOPPED -> "USER_STOPPED"
        ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "PERMISSION_CHANGE"
        ApplicationExitInfo.REASON_EXIT_SELF -> "EXIT_SELF"
        else -> "OTHER($reason)"
    }
}
