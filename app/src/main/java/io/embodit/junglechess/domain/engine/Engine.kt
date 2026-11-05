package io.embodit.junglechess.domain.engine

import io.embodit.junglechess.domain.model.Animal
import io.embodit.junglechess.domain.model.GameStatus
import io.embodit.junglechess.domain.model.Move
import io.embodit.junglechess.domain.model.Pieces
import io.embodit.junglechess.domain.model.Position
import io.embodit.junglechess.domain.model.Side
import io.embodit.junglechess.domain.model.Sq
import io.embodit.junglechess.domain.model.Terrain
import io.embodit.junglechess.domain.rules.JungleRules
import kotlin.random.Random

/**
 * Five AI levels (design package gameSpec.ai): negamax alpha-beta with iterative deepening, capture-only
 * quiescence, history ordering and a time limit. Level 1 is depth 1, drops moves that hand a piece over for
 * free, then picks one of the best five at random (B27: a first-timer can win); level 2 depth 2, random of
 * the best three. Deterministic for a given seed.
 */
class Engine(seed: Long = 7L) {
    private val random = Random(seed)
    private val history = HashMap<Long, Int>()

    data class Level(val n: Int, val depth: Int, val timeMs: Long, val randomTop: Int, val safeFilter: Boolean, val wander: Float = 0f)

    companion object {
        val LEVELS = listOf(
            Level(1, depth = 1, timeMs = 0, randomTop = 5, safeFilter = true, wander = WANDER),
            Level(2, depth = 2, timeMs = 0, randomTop = 3, safeFilter = false),
            Level(3, depth = 3, timeMs = 300, randomTop = 1, safeFilter = false),
            Level(4, depth = 4, timeMs = 800, randomTop = 1, safeFilter = false),
            Level(5, depth = 7, timeMs = 2000, randomTop = 1, safeFilter = false),
        )
        /** Beginner: share of moves that just wander (any legal move), so a first-timer can win (B27). */
        const val WANDER = 0.5f
        const val HINT_LEVEL = 3
        const val WIN = 100_000
        private val VALUE = intArrayOf(0, 200, 300, 400, 500, 600, 700, 800, 900) // by rank: rat 2 .. elephant 9 (x100)

        fun level(n: Int): Level = LEVELS[(n - 1).coerceIn(0, LEVELS.size - 1)]
    }

    /** Thrown to unwind a search that ran out of time or was cancelled. */
    private class Stop : RuntimeException(null, null, false, false)

    private var deadline = Long.MAX_VALUE
    private var cancelled: () -> Boolean = { false }
    private var clock: () -> Long = { System.currentTimeMillis() }
    private var nodes = 0L
    var lastDepth = 0
        private set
    var lastNodes = 0L
        private set

    /**
     * Best move for the side to move at [levelN] (1..5), or null when there is none.
     * [timeLimited] = false ignores the level's time budget and searches to its full depth (tests).
     */
    fun choose(pos: Position, levelN: Int, cancel: () -> Boolean = { false }, now: () -> Long = { System.currentTimeMillis() }, timeLimited: Boolean = true): Move? {
        val level = level(levelN)
        val moves = JungleRules.allLegalMoves(pos)
        if (moves.isEmpty()) return null
        cancelled = cancel
        clock = now
        nodes = 0
        deadline = if (timeLimited && level.timeMs > 0) now() + level.timeMs else Long.MAX_VALUE
        // immediate win: walk into the den (the beginner may miss it while wandering)
        if (level.wander == 0f) moves.firstOrNull { it.to == Terrain.den(pos.toMove.other) }?.let { return it }

        if (level.wander > 0f && random.nextFloat() < level.wander) return moves[random.nextInt(moves.size)]
        var candidates = moves
        if (level.safeFilter) {
            val safe = moves.filter { !hangsPiece(pos, it) }
            if (safe.isNotEmpty()) candidates = safe
        }
        val scored = if (level.randomTop > 1) {
            candidates.map { it to -searchRoot(JungleRules.apply(pos, it), level.depth - 1) }
                .sortedByDescending { it.second }
        } else {
            iterative(pos, candidates, level.depth)
        }
        lastNodes = nodes
        if (scored.isEmpty()) return candidates.first()
        val top = scored.take(level.randomTop.coerceAtMost(scored.size))
        // among equally good best moves, pick one at random so games differ
        val pool = if (level.randomTop > 1) top else scored.filter { it.second == scored.first().second }
        return pool[random.nextInt(pool.size)].first
    }

    /** Engine suggestion for the hint (fixed at level 3 whatever the opponent level). */
    fun hint(pos: Position, cancel: () -> Boolean = { false }): Move? = choose(pos, HINT_LEVEL, cancel)

    private fun searchRoot(pos: Position, depth: Int): Int = try {
        negamax(pos, depth, -WIN * 2, WIN * 2, 1)
    } catch (e: Stop) {
        0
    }

    private fun iterative(pos: Position, moves: List<Move>, maxDepth: Int): List<Pair<Move, Int>> {
        var best: List<Pair<Move, Int>> = moves.map { it to 0 }
        var ordered = order(moves, pos)
        for (depth in 1..maxDepth) {
            val results = ArrayList<Pair<Move, Int>>()
            try {
                var alpha = -WIN * 2
                for (m in ordered) {
                    val v = -negamax(JungleRules.apply(pos, m), depth - 1, -WIN * 2, -alpha + 1, 1)
                    results += m to v
                    if (v > alpha) alpha = v
                }
            } catch (e: Stop) {
                break
            }
            best = results.sortedByDescending { it.second }
            lastDepth = depth
            ordered = best.map { it.first }
            if (best.first().second >= WIN - 100) break
            if (clock() > deadline) break
        }
        return best
    }

    private fun checkStop() {
        if ((++nodes and 1023L) == 0L) {
            if (cancelled() || clock() > deadline) throw Stop()
        }
    }

    private fun negamax(pos: Position, depth: Int, alphaIn: Int, beta: Int, ply: Int): Int {
        checkStop()
        when (val st = JungleRules.status(pos)) {
            is GameStatus.Won -> return if (st.winner == pos.toMove) WIN - ply else -WIN + ply
            GameStatus.Ongoing -> {}
        }
        if (depth <= 0) return quiesce(pos, alphaIn, beta, ply, 4)
        var alpha = alphaIn
        var best = -WIN * 2
        for (m in order(JungleRules.allLegalMoves(pos), pos)) {
            val v = -negamax(JungleRules.apply(pos, m), depth - 1, -beta, -alpha, ply + 1)
            if (v > best) best = v
            if (v > alpha) alpha = v
            if (alpha >= beta) {
                val k = key(m)
                history[k] = (history[k] ?: 0) + depth * depth
                break
            }
        }
        return best
    }

    private fun quiesce(pos: Position, alphaIn: Int, beta: Int, ply: Int, left: Int): Int {
        checkStop()
        when (val st = JungleRules.status(pos)) {
            is GameStatus.Won -> return if (st.winner == pos.toMove) WIN - ply else -WIN + ply
            GameStatus.Ongoing -> {}
        }
        val stand = evaluate(pos, pos.toMove)
        if (left == 0 || stand >= beta) return stand
        var alpha = maxOf(alphaIn, stand)
        val den = Terrain.den(pos.toMove.other)
        val noisy = JungleRules.allLegalMoves(pos).filter { it.captured >= 0 || it.to == den }
        for (m in order(noisy, pos)) {
            val v = -quiesce(JungleRules.apply(pos, m), -beta, -alpha, ply + 1, left - 1)
            if (v >= beta) return v
            if (v > alpha) alpha = v
        }
        return alpha
    }

    private fun key(m: Move): Long = (m.piece.toLong() shl 16) or (m.from.toLong() shl 8) or m.to.toLong()

    private fun order(moves: List<Move>, pos: Position): List<Move> {
        val den = Terrain.den(pos.toMove.other)
        return moves.sortedByDescending { m ->
            var s = history[key(m)] ?: 0
            if (m.to == den) s += 10_000_000
            if (m.captured >= 0) s += 1_000_000 + Pieces.animal(m.captured).rank * 1000 - Pieces.animal(m.piece).rank
            s
        }
    }

    /** True when the opponent can capture the moved piece right away and it took nothing worth it. */
    private fun hangsPiece(pos: Position, m: Move): Boolean {
        val next = JungleRules.apply(pos, m)
        val attacked = JungleRules.allLegalMoves(next).any { it.to == m.to }
        if (!attacked) return false
        val gain = if (m.captured >= 0) Pieces.animal(m.captured).rank else 0
        return gain < Pieces.animal(m.piece).rank
    }

    // ------------------------------------------------------------------ evaluation

    /** Score from [side]'s point of view (centi-units: a rat is worth 200). */
    fun evaluate(pos: Position, side: Side): Int = score(pos, side) - score(pos, side.other)

    private fun score(pos: Position, side: Side): Int {
        val enemy = side.other
        val enemyHasRat = pos.squareOf(Pieces.id(enemy, Animal.RAT)) >= 0
        val ownRat = pos.squareOf(Pieces.id(side, Animal.RAT)) >= 0
        val targetDen = Terrain.den(enemy)
        var s = 0
        val dists = ArrayList<Int>(8)
        var defenders = 0
        val ownTraps = Terrain.traps(side)
        for (sq in 0 until Sq.COUNT) {
            val id = pos.at(sq)
            if (id < 0 || Pieces.side(id) != side) continue
            val a = Pieces.animal(id)
            var v = VALUE[a.rank]
            if (a == Animal.ELEPHANT) {
                if (enemyHasRat) v = 700
                if (ownRat) v += 100
            }
            if (Terrain.trapOwner(sq) == enemy) v -= v / 2
            s += v
            dists += distance(sq, targetDen, a)
            if (sq in ownTraps || isDenGuard(sq, side)) defenders++
        }
        // advance: the three closest pieces to the enemy den
        dists.sort()
        for (d in dists.take(3)) s += 300 / (d + 1)
        s += defenders * 50
        return s
    }

    private fun isDenGuard(sq: Int, side: Side): Boolean {
        val den = Terrain.den(side)
        return kotlin.math.abs(Sq.col(sq) - Sq.col(den)) + kotlin.math.abs(Sq.row(sq) - Sq.row(den)) == 2
    }

    /** Manhattan distance to the den, plus a detour for animals that cannot cross the river columns. */
    private fun distance(sq: Int, den: Int, a: Animal): Int {
        val d = kotlin.math.abs(Sq.col(sq) - Sq.col(den)) + kotlin.math.abs(Sq.row(sq) - Sq.row(den))
        val c = Sq.col(sq)
        val r = Sq.row(sq)
        val crossing = (r <= 6 && Sq.row(den) == 9 || r >= 4 && Sq.row(den) == 1)
        val inRiverColumn = c in 1..2 || c in 4..5
        return if (crossing && inRiverColumn && !a.canSwim && !a.canLeap) d + 2 else d
    }
}
