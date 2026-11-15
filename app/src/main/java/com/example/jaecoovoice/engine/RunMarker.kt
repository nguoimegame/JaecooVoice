package com.example.jaecoovoice.engine

import java.io.File

/**
 * Remembers which run of this app (and which build) is the current one, so the next process can say, when it
 * reports how its predecessor died, which run that was. Without it the report would be filed under the run
 * that happens to be telling it.
 */
object RunMarker {
    private const val FILE = "last-run.txt"
    private const val PARTS = 3

    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    fun write(dir: File, run: LogRun) {
        try {
            File(dir, FILE).writeText("${run.run}|${run.version}|${run.versionCode}")
        } catch (e: Exception) {
            // A missing marker only costs the attribution of the next death report.
        }
    }

    /** The run the previous process wrote, or null on the first start or when the file is not ours. */
    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    fun read(dir: File): LogRun? = try {
        val parts = File(dir, FILE).takeIf { it.isFile }?.readText()?.split('|')
        val code = parts?.getOrNull(2)?.toLongOrNull()
        if (parts?.size == PARTS && parts[0].isNotEmpty() && code != null) LogRun(parts[1], code, parts[0]) else null
    } catch (e: Exception) {
        null
    }
}
