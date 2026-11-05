package io.embodit.junglechess.ui.board

import kotlin.math.PI
import kotlin.math.sin

internal object Easing {
    fun inOut(t: Float): Float = if (t < 0.5f) 4f * t * t * t else 1f - (-2f * t + 2f).let { it * it * it } / 2f
    fun out(t: Float): Float = 1f - (1f - t).let { it * it * it }
    fun hump(t: Float): Float = sin((PI * t).toFloat())
}

/** One animated stretch on the timeline. [update] receives local progress 0..1. */
internal class Clip(
    val start: Float,
    val duration: Float,
    val onStart: () -> Unit = {},
    val update: (Float) -> Unit,
    val onEnd: () -> Unit = {},
) {
    var started = false
    var ended = false
    val end: Float get() = start + duration
}

/** Runs clips against a clock in seconds; clips may overlap. [onComplete] fires once after the last one. */
internal class Timeline(private val clips: List<Clip>, private val tail: Float = 0f, private val onComplete: () -> Unit) {
    private var time = 0f
    private var completed = false
    val total: Float = (clips.maxOfOrNull { it.end } ?: 0f) + tail

    fun advance(dt: Float): Boolean {
        if (completed) return true
        time += dt
        for (clip in clips) {
            if (clip.ended) continue
            if (time < clip.start) continue
            if (!clip.started) { clip.started = true; clip.onStart() }
            val u = if (clip.duration <= 0f) 1f else ((time - clip.start) / clip.duration).coerceIn(0f, 1f)
            clip.update(u)
            if (time >= clip.end) { clip.ended = true; clip.onEnd() }
        }
        if (time >= total) { completed = true; onComplete() }
        return completed
    }

    /** Jumps to the end: every clip lands in its final state. */
    fun finish() {
        if (completed) return
        for (clip in clips) {
            if (!clip.started) { clip.started = true; clip.onStart() }
            clip.update(1f)
            if (!clip.ended) { clip.ended = true; clip.onEnd() }
        }
        completed = true
        onComplete()
    }
}
