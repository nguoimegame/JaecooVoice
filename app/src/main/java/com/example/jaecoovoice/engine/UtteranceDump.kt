package com.example.jaecoovoice.engine

import android.content.Context
import android.content.pm.ApplicationInfo
import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Keeps the audio of a listen next to what was recognized; [EngineCore] calls it once per heard utterance. */
fun interface UtteranceDump {
    fun save(samples: FloatArray, text: String)

    companion object {
        val NONE = UtteranceDump { _, _ -> }

        /**
         * Debug builds only, and only while the file `save_wav` exists in the app's files dir
         * (`adb shell run-as com.example.jaecoovoice touch files/save_wav`). Used to record the
         * test set for docs/superpowers/specs/2026-11-12-voice-hotwords-design.md on the car.
         */
        fun forApp(app: Context): UtteranceDump {
            val debuggable = app.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
            if (!debuggable) return NONE
            val marker = File(app.filesDir, WavDump.MARKER)
            val dir = File(app.filesDir, WavDump.DIR)
            return UtteranceDump { samples, text ->
                if (marker.exists()) {
                    try {
                        WavDump.write(dir, samples, text, System.currentTimeMillis())
                    } catch (e: java.io.IOException) {
                        Log.w("UtteranceDump", "could not save the utterance", e)
                    }
                }
            }
        }
    }
}

/** Writes `<millis>.wav` (16 kHz mono PCM16) and `<millis>.txt` (what was recognized) into a folder of at most [KEEP] pairs. */
object WavDump {
    const val MARKER = "save_wav"
    const val DIR = "wav"
    const val KEEP = 50

    fun write(dir: File, samples: FloatArray, text: String, millis: Long) {
        dir.mkdirs()
        File(dir, "$millis.wav").writeBytes(wav(samples, AudioCapture.SAMPLE_RATE))
        File(dir, "$millis.txt").writeText(text)
        prune(dir)
    }

    private fun prune(dir: File) {
        val stems = dir.listFiles { f -> f.extension == "wav" }.orEmpty().map { it.nameWithoutExtension }.sorted()
        stems.dropLast(KEEP).forEach { stem ->
            File(dir, "$stem.wav").delete()
            File(dir, "$stem.txt").delete()
        }
    }

    internal fun wav(samples: FloatArray, sampleRate: Int): ByteArray {
        val dataBytes = samples.size * BYTES_PER_SAMPLE
        val header = ByteBuffer.allocate(HEADER).order(ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray()).putInt(HEADER - RIFF_PREFIX + dataBytes).put("WAVE".toByteArray())
        header.put("fmt ".toByteArray()).putInt(FMT_SIZE).putShort(PCM).putShort(MONO)
        header.putInt(sampleRate).putInt(sampleRate * BYTES_PER_SAMPLE).putShort(BYTES_PER_SAMPLE.toShort()).putShort(BITS)
        header.put("data".toByteArray()).putInt(dataBytes)
        val body = ByteBuffer.allocate(dataBytes).order(ByteOrder.LITTLE_ENDIAN)
        for (s in samples) body.putShort((s.coerceIn(-1f, 1f) * Short.MAX_VALUE).toInt().toShort())
        return ByteArrayOutputStream(HEADER + dataBytes).apply {
            write(header.array())
            write(body.array())
        }.toByteArray()
    }

    private const val HEADER = 44
    private const val RIFF_PREFIX = 8
    private const val FMT_SIZE = 16
    private const val PCM: Short = 1
    private const val MONO: Short = 1
    private const val BITS: Short = 16
    private const val BYTES_PER_SAMPLE = 2
}
