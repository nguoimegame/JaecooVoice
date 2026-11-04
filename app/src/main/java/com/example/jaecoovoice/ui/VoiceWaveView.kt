package com.example.jaecoovoice.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import com.example.jaecoovoice.R
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Assistant-style waves (copied from JaecooApp's voice overlay): four colour bands added on top of
 * each other, amplitude following [level] (microphone RMS), a quick small ripple while [thinking]
 * (recognizing). Redraws every frame while shown; [level] is written from the recording thread.
 */
class VoiceWaveView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {

    /** Latest microphone RMS (0–1), written from any thread. */
    @Volatile
    var level = 0f

    var thinking = false

    private class Wave(val color: Int, val frequency: Float, val speed: Float, val scale: Float, val offset: Float)

    private val waves = listOf(
        Wave(color(R.color.voice_wave_blue), 1.6f, 1.0f, 1.0f, 0f),
        Wave(color(R.color.voice_wave_pink), 2.2f, 1.35f, 0.8f, 1.7f),
        Wave(color(R.color.voice_wave_cyan), 1.2f, 0.8f, 0.7f, 3.1f),
        Wave(color(R.color.voice_wave_purple), 2.8f, 1.6f, 0.55f, 4.4f)
    )

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        xfermode = PorterDuffXfermode(PorterDuff.Mode.ADD)
    }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = color(R.color.voice_hud_text_muted)
        strokeWidth = resources.displayMetrics.density
    }
    private val path = Path()

    /** Amplitude being drawn (0–1), easing towards its target so the waves do not jump. */
    private var amplitude = IDLE
    private var phase = 0f
    private var lastFrameNanos = 0L

    init {
        // The ADD xfermode needs its own layer.
        setLayerType(LAYER_TYPE_HARDWARE, null)
    }

    private fun color(id: Int) = ContextCompat.getColor(context, id)

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val now = System.nanoTime()
        val dt = if (lastFrameNanos == 0L) FRAME_S else ((now - lastFrameNanos) / NANOS_PER_S).coerceAtMost(MAX_DT_S)
        lastFrameNanos = now
        step(dt)

        val mid = height / 2f
        canvas.drawLine(0f, mid, width.toFloat(), mid, line)
        for (wave in waves) {
            fill.color = wave.color
            fill.alpha = WAVE_ALPHA
            canvas.drawPath(wavePath(wave, mid), fill)
        }
        if (isShown) postInvalidateOnAnimation()
    }

    private fun step(dt: Float) {
        val target = if (thinking) THINKING else IDLE + min(1f, sqrt(level / LOUD_RMS)) * (1f - IDLE)
        amplitude += (target - amplitude) * min(1f, dt * EASE_PER_S)
        phase += dt * (if (thinking) THINKING_SPEED else SPEED)
    }

    /** One band: a sine between two fading edges, mirrored across the middle line and filled. */
    private fun wavePath(wave: Wave, mid: Float): Path {
        path.reset()
        val w = width.toFloat()
        val half = height / 2f * amplitude * wave.scale
        path.moveTo(0f, mid)
        var x = 0f
        while (x <= w) {
            path.lineTo(x, mid - offsetAt(wave, x / w, half))
            x += STEP_PX
        }
        x = w
        while (x >= 0f) {
            path.lineTo(x, mid + offsetAt(wave, x / w, half))
            x -= STEP_PX
        }
        path.close()
        return path
    }

    private fun offsetAt(wave: Wave, t: Float, half: Float): Float {
        val n = t * 2f - 1f
        val envelope = (1f - n * n).let { it * it }
        return half * envelope * sin(wave.frequency * n * PI.toFloat() + phase * wave.speed + wave.offset)
    }

    private companion object {
        const val IDLE = 0.2f
        const val THINKING = 0.3f

        /** RMS that counts as loud speech (full waves). */
        const val LOUD_RMS = 0.12f
        const val EASE_PER_S = 12f
        const val SPEED = 6f
        const val THINKING_SPEED = 14f
        const val WAVE_ALPHA = 150
        const val STEP_PX = 6f
        const val FRAME_S = 1f / 60f
        const val MAX_DT_S = 0.1f
        const val NANOS_PER_S = 1_000_000_000f
    }
}
