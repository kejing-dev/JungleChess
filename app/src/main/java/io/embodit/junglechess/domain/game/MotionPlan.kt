package io.embodit.junglechess.domain.game

import io.embodit.junglechess.domain.model.Animal
import io.embodit.junglechess.domain.model.Move
import io.embodit.junglechess.domain.model.Pieces
import io.embodit.junglechess.domain.model.Sq
import io.embodit.junglechess.domain.model.Terrain

/**
 * What the board plays for a committed move and how long it takes (design package states S05 / S17).
 * Shared by the board scene (timeline) and the game flow (watchdog), so they never disagree.
 */
data class MotionPlan(
    val kind: Kind,
    /** seconds */
    val travel: Float,
    val captureDelay: Float,
    val captureDuration: Float,
    val settle: Float,
    val intoRiver: Boolean,
    val outOfRiver: Boolean,
    val intoTrap: Boolean,
) {
    enum class Kind { WALK, JUMP, RAT_TOPPLES_ELEPHANT }

    val total: Float get() = maxOf(travel + settle, captureDelay + captureDuration)
    val totalMs: Long get() = (total * 1000).toLong()

    companion object {
        const val WALK = 0.22f
        const val JUMP_ACROSS = 0.42f // two river columns
        const val JUMP_ALONG = 0.52f // three river rows
        const val SHRINK = 0.24f
        const val TOPPLE = 0.30f
        const val RIPPLE = 0.40f
        const val CROUCH = 0.20f
        const val REDUCED = 0.20f
        const val JUMP_APEX = 0.08f
        const val LIFT = 0.03f

        fun of(move: Move, reduced: Boolean): MotionPlan {
            val animal = Pieces.animal(move.piece)
            val captured = if (move.captured >= 0) Pieces.animal(move.captured) else null
            val jump = move.isJump
            val kind = when {
                jump -> Kind.JUMP
                animal == Animal.RAT && captured == Animal.ELEPHANT -> Kind.RAT_TOPPLES_ELEPHANT
                else -> Kind.WALK
            }
            val intoRiver = Terrain.isRiver(move.to) && !Terrain.isRiver(move.from)
            val outOfRiver = Terrain.isRiver(move.from) && !Terrain.isRiver(move.to)
            val trapOwner = Terrain.trapOwner(move.to)
            val intoTrap = trapOwner != null && trapOwner != Pieces.side(move.piece)
            if (reduced) {
                return MotionPlan(kind, REDUCED, 0f, if (captured != null) REDUCED else 0f, 0f, intoRiver, outOfRiver, intoTrap)
            }
            val travel = when (kind) {
                Kind.JUMP -> if (Sq.row(move.from) == Sq.row(move.to)) JUMP_ACROSS else JUMP_ALONG
                else -> WALK
            }
            val settle = when {
                intoRiver -> RIPPLE
                intoTrap -> CROUCH
                kind == Kind.JUMP -> 0.12f
                else -> 0f
            }
            val (delay, dur) = when {
                captured == null -> 0f to 0f
                kind == Kind.RAT_TOPPLES_ELEPHANT -> travel to TOPPLE
                else -> travel * 0.6f to SHRINK
            }
            return MotionPlan(kind, travel, delay, dur, settle, intoRiver, outOfRiver, intoTrap)
        }
    }
}
