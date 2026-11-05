package io.embodit.junglechess.ui.window

import kotlin.math.abs

/**
 * Opens a system window only while the head is level (prompts/snippets/native-window-move.md §7.9, D9):
 * the caller feeds the time and the head pitch every frame (degrees, + up; null = no valid head pose).
 * In this app (Shared Space volume) the SDK gives no head pose at all (feedback/pico-cli-feedback.md
 * "Shared Space … provider 返回 NOT_IN_FULL_SPACE"), so every request ends on the no-head fallback after
 * [noHeadMs]; the gate stays so a future head-pose source can be plugged in without touching the callers.
 */
class LevelGate(val maxPitchDeg: Float = 10f, val dwellMs: Long = 300L, val noHeadMs: Long = 5000L) {
    var pending: String? = null
        private set
    private var askedMs = 0L
    private var levelSinceMs = -1L
    private var noHeadSinceMs = -1L
    val waiting get() = pending != null

    fun ask(why: String, now: Long) {
        if (pending == null) askedMs = now
        pending = why; levelSinceMs = -1L; noHeadSinceMs = -1L
    }

    fun cancel() { pending = null; levelSinceMs = -1L; noHeadSinceMs = -1L }

    fun waitedMs(now: Long) = if (pending == null) 0L else now - askedMs

    /** Returns the reason to open now, or null to keep waiting. */
    fun step(now: Long, pitchDeg: Float?): String? {
        val why = pending ?: return null
        if (pitchDeg == null || !pitchDeg.isFinite()) {
            levelSinceMs = -1L
            if (noHeadSinceMs < 0L) noHeadSinceMs = now // counted from the first frame, not from the request
            if (now - noHeadSinceMs < noHeadMs) return null
            cancel(); return "$why/nohead"
        }
        noHeadSinceMs = -1L
        if (abs(pitchDeg) > maxPitchDeg) { levelSinceMs = -1L; return null }
        if (levelSinceMs < 0L) levelSinceMs = now
        if (now - levelSinceMs < dwellMs) return null
        cancel(); return why
    }

    fun hintWanted(pitchDeg: Float?) = pending != null && pitchDeg != null && abs(pitchDeg) > maxPitchDeg
}
