package io.embodit.junglechess.ui.board

import io.embodit.junglechess.domain.model.Animal
import io.embodit.junglechess.domain.model.Pieces
import io.embodit.junglechess.domain.model.Side
import io.embodit.junglechess.domain.model.Sq
import io.embodit.junglechess.domain.model.Terrain
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** A point / vector in metres. */
data class P3(val x: Float, val y: Float, val z: Float) {
    operator fun plus(o: P3) = P3(x + o.x, y + o.y, z + o.z)
    operator fun minus(o: P3) = P3(x - o.x, y - o.y, z - o.z)
    operator fun times(k: Float) = P3(x * k, y * k, z * k)
    fun lerp(o: P3, t: Float) = P3(x + (o.x - x) * t, y + (o.y - y) * t, z + (o.z - z) * t)
    fun length(): Float = sqrt(x * x + y * y + z * z)
    fun dot(o: P3) = x * o.x + y * o.y + z * o.z
}

/**
 * Geometry of the board in the volume (design package spatialLayout; units metres).
 *
 * Volume space: origin at the volume centre, +X right, +Y up, +Z toward the user; volume 0.56 x 0.36 x 0.72.
 * Board space: origin on the nominal turf top at the board centre, same axes before the tilt. The board
 * (0.50 x 0.62 x 0.025, tools/asset-pipeline/board.mjs) tips toward the user by [TILT_DEG] about the near
 * bottom edge, which rests at (y -0.18, z +0.29) on the volume floor.
 *
 * Every number the scene and the D35 / D16 unit tests use comes from here.
 */
object BoardLayout {
    const val VOL_W = 0.56f
    const val VOL_H = 0.36f
    const val VOL_D = 0.72f
    const val FLOOR = -VOL_H / 2

    const val PITCH = 0.06f
    const val HALF_W = 0.25f
    const val HALF_D = 0.31f
    const val THICK = 0.025f
    const val TILT_DEG = 20f
    val TILT = Math.toRadians(TILT_DEG.toDouble()).toFloat()

    /** Near bottom edge of the board, volume space. */
    val PIVOT = P3(BOARD_SHIFT_X, FLOOR, 0.29f)

    /** The board sits 2 cm left of the volume centre so the control window (system-placed on the right) keeps a clear view (D20). */
    const val BOARD_SHIFT_X = -0.02f

    const val BASE_R = 0.023f
    const val BASE_H = 0.008f
    const val LIFT_SELECTED = 0.008f
    const val LIFT_MOVE = 0.03f
    const val RIVER_SINK = -0.014f // base top level with the water (water at -6 mm)
    const val TRAP_SINK = -0.008f
    const val TRAP_PITCH_DEG = -12f
    const val DEN_SINK = -0.004f
    const val TRAY_SCALE = 0.5f

    /** Animal heights after calibration (tools/asset-pipeline/animals.mjs -> calibration-report.json). */
    fun animalHeight(a: Animal): Float = when (a) {
        Animal.ELEPHANT -> 0.0454f
        Animal.LION -> 0.0439f
        Animal.TIGER -> 0.0394f
        Animal.LEOPARD -> 0.0371f
        Animal.WOLF -> 0.0430f
        Animal.DOG -> 0.0400f
        Animal.CAT -> 0.0370f
        Animal.RAT -> 0.0319f
    }

    /** Tallest piece (base + animal), for the envelope tests. */
    val TALLEST: Float get() = BASE_H + Animal.entries.maxOf { animalHeight(it) }

    fun colX(col: Int): Float = (col - 3) * PITCH
    fun rowZ(row: Int): Float = (row - 5) * PITCH

    /** Square centre on the turf, board space. */
    fun square(sq: Int): P3 = P3(colX(Sq.col(sq)), 0f, rowZ(Sq.row(sq)))

    /** Where a piece's base bottom rests on [sq] (board space), including the river / trap / den sinks. */
    fun rest(sq: Int, side: Side): P3 {
        val p = square(sq)
        val y = when {
            Terrain.isRiver(sq) -> RIVER_SINK
            Terrain.trapOwner(sq).let { it != null && it != side } -> TRAP_SINK
            Terrain.denOwner(sq) != null -> DEN_SINK
            else -> 0.0005f
        }
        return P3(p.x, y, p.z)
    }

    fun inEnemyTrap(sq: Int, side: Side): Boolean = Terrain.trapOwner(sq).let { it != null && it != side }

    /** Facing (radians about +Y): the models face -Z; red walks toward -Z, blue toward +Z. */
    fun yaw(side: Side): Float = if (side == Side.RED) 0f else Math.PI.toFloat()

    /** Board space -> volume space (tilt about the near bottom edge, [tiltDeg] for the 0 / 10 / 20 envelope tests). */
    fun toVolume(p: P3, tiltDeg: Float = TILT_DEG): P3 {
        val t = Math.toRadians(tiltDeg.toDouble()).toFloat()
        val pivotBoard = P3(0f, -THICK, HALF_D)
        val d = p - pivotBoard
        val y = d.y * cos(t) - d.z * sin(t)
        val z = d.y * sin(t) + d.z * cos(t)
        return P3(d.x + PIVOT.x, y + PIVOT.y, z + PIVOT.z)
    }

    /** Volume space -> board space (inverse of [toVolume]). */
    fun toBoard(v: P3, tiltDeg: Float = TILT_DEG): P3 {
        val t = Math.toRadians(tiltDeg.toDouble()).toFloat()
        val d = v - PIVOT
        val y = d.y * cos(t) + d.z * sin(t)
        val z = -d.y * sin(t) + d.z * cos(t)
        return P3(d.x, y - THICK, z + HALF_D)
    }

    /** Board normal in volume space. */
    fun up(tiltDeg: Float = TILT_DEG): P3 {
        val t = Math.toRadians(tiltDeg.toDouble()).toFloat()
        return P3(0f, cos(t), sin(t))
    }

    // ---- trays and cards (volume space, not tilted) ----

    /** Tray centres: left = red's captures, right = blue's captures; floor-level, in front of the board. */
    fun trayCenter(capturer: Side): P3 = P3(if (capturer == Side.RED) -0.17f else 0.17f, FLOOR, 0.33f)
    const val TRAY_W = 0.14f
    const val TRAY_D = 0.05f
    const val TRAY_RIM = 0.015f
    const val TRAY_FLOOR = 0.003f

    /** Slot [k] (0..7) in [capturer]'s tray: 2 rows of 4. */
    fun traySlot(capturer: Side, k: Int): P3 {
        val c = trayCenter(capturer)
        val col = k % 4
        val row = (k / 4).coerceAtMost(1)
        return P3(c.x - 0.0495f + col * 0.033f, FLOOR + TRAY_FLOOR, c.z - 0.0105f + row * 0.021f)
    }

    val SEAT_CARD = P3(0f, -0.155f, 0.33f)
    const val SEAT_W = 0.14f
    const val SEAT_H = 0.03f
    fun countCard(capturer: Side): P3 = trayCenter(capturer).let { P3(it.x, FLOOR + 0.0075f, it.z + TRAY_D / 2 + 0.001f) }
    const val COUNT_W = 0.06f
    const val COUNT_H = 0.015f

    /** Collision box of a piece: covers the base and the animal (D17). */
    fun pieceColliderHeight(id: Int): Float = BASE_H + animalHeight(Pieces.animal(id)) + 0.004f
}
