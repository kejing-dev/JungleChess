package io.embodit.junglechess.domain.rules

import io.embodit.junglechess.domain.model.Animal
import io.embodit.junglechess.domain.model.GameStatus
import io.embodit.junglechess.domain.model.Move
import io.embodit.junglechess.domain.model.Pieces
import io.embodit.junglechess.domain.model.Position
import io.embodit.junglechess.domain.model.Side
import io.embodit.junglechess.domain.model.Sq
import io.embodit.junglechess.domain.model.Terrain
import io.embodit.junglechess.domain.model.WinReason
import kotlin.math.abs

/** Why a tap on a square cannot be a move (T2: red frame + one sentence). */
enum class Illegal {
    TOO_FAR,          // not one square in a straight line (and not a lion / tiger leap)
    OWN_DEN,          // into your own den
    ONLY_RAT_SWIMS,   // a non-rat into the river
    RAT_BLOCKS_JUMP,  // lion / tiger leap with a rat in the way
    OWN_PIECE,        // onto one of your own
    ELEPHANT_RAT,     // elephant onto a rat
    WATER,            // land <-> river capture
    OUTRANKED,        // the target is bigger
}

/**
 * The standard Chinese rule set of the design package (gameSpec.specialRules), fixed, no variants:
 * one square orthogonally; only the rat enters the river; lion and tiger leap the river in a straight line
 * unless any rat (either side) is in the way; rank >= rank captures; the rat captures the elephant from land,
 * the elephant never captures the rat; a rat in the river and a piece on land cannot capture each other;
 * a piece in the opponent's trap counts as rank 0; no piece enters its own den; entering the other den wins;
 * capturing everything wins; having no move loses.
 */
object JungleRules {
    private val DIRS = arrayOf(intArrayOf(1, 0), intArrayOf(-1, 0), intArrayOf(0, 1), intArrayOf(0, -1))

    /** Rank of the piece on [sq] as a defender: 0 inside the opponent's trap. */
    fun defenceRank(pos: Position, sq: Int): Int {
        val id = pos.at(sq)
        if (id < 0) return 0
        val owner = Terrain.trapOwner(sq)
        return if (owner != null && owner != Pieces.side(id)) 0 else Pieces.animal(id).rank
    }

    /** null when [attacker] on [from] may capture the piece on [to]; otherwise why not. */
    fun captureBlock(pos: Position, from: Int, to: Int): Illegal? {
        val a = pos.at(from)
        val d = pos.at(to)
        if (a < 0 || d < 0) return null
        if (Pieces.side(a) == Pieces.side(d)) return Illegal.OWN_PIECE
        val aRiver = Terrain.isRiver(from)
        val dRiver = Terrain.isRiver(to)
        if (aRiver != dRiver) return Illegal.WATER
        if (defenceRank(pos, to) == 0) return null
        val aa = Pieces.animal(a)
        val da = Pieces.animal(d)
        if (aa == Animal.RAT && da == Animal.ELEPHANT) return null
        if (aa == Animal.ELEPHANT && da == Animal.RAT) return Illegal.ELEPHANT_RAT
        return if (aa.rank >= da.rank) null else Illegal.OUTRANKED
    }

    /** Destination of a step from [from] in direction (dc, dr) for [animal], or -1. Leaps over the river. */
    private fun stepTarget(pos: Position, from: Int, animal: Animal, dc: Int, dr: Int): Int {
        var c = Sq.col(from) + dc
        var r = Sq.row(from) + dr
        if (!Sq.valid(c, r)) return -1
        var sq = Sq.of(c, r)
        if (Terrain.isRiver(sq) && !animal.canSwim) {
            if (!animal.canLeap) return -1
            while (Sq.valid(c, r) && Terrain.isRiver(Sq.of(c, r))) {
                val q = pos.at(Sq.of(c, r))
                if (q >= 0 && Pieces.animal(q) == Animal.RAT) return -1
                c += dc; r += dr
            }
            if (!Sq.valid(c, r)) return -1
            sq = Sq.of(c, r)
        }
        return sq
    }

    fun legalMovesFrom(pos: Position, from: Int): List<Move> {
        val id = pos.at(from)
        if (id < 0) return emptyList()
        val side = Pieces.side(id)
        val animal = Pieces.animal(id)
        val out = ArrayList<Move>(4)
        for (d in DIRS) {
            val to = stepTarget(pos, from, animal, d[0], d[1])
            if (to < 0) continue
            if (Terrain.denOwner(to) == side) continue
            val q = pos.at(to)
            if (q >= 0) {
                if (captureBlock(pos, from, to) != null) continue
                out += Move(from, to, id, q)
            } else {
                out += Move(from, to, id)
            }
        }
        return out
    }

    fun allLegalMoves(pos: Position, side: Side = pos.toMove): List<Move> {
        val out = ArrayList<Move>(32)
        for (s in 0 until Sq.COUNT) {
            val id = pos.at(s)
            if (id >= 0 && Pieces.side(id) == side) out += legalMovesFrom(pos, s)
        }
        return out
    }

    fun hasMove(pos: Position, side: Side): Boolean {
        for (s in 0 until Sq.COUNT) {
            val id = pos.at(s)
            if (id >= 0 && Pieces.side(id) == side && legalMovesFrom(pos, s).isNotEmpty()) return true
        }
        return false
    }

    fun find(pos: Position, from: Int, to: Int): Move? = legalMovesFrom(pos, from).firstOrNull { it.to == to }

    fun isLegal(pos: Position, move: Move): Boolean =
        pos.at(move.from) == move.piece && Pieces.side(move.piece) == pos.toMove && find(pos, move.from, move.to) == move

    /** Applies a legal move and passes the turn. */
    fun apply(pos: Position, move: Move): Position {
        val cells = pos.cellsCopy()
        cells[move.to] = cells[move.from]
        cells[move.from] = 0
        return Position.of(cells, pos.toMove.other)
    }

    /**
     * Status after a move: the side that just moved ([Position.toMove].other) wins by entering the den or by
     * capturing everything; the side to move loses when it has no legal move.
     */
    fun status(pos: Position): GameStatus {
        for (side in Side.entries) {
            val den = pos.at(Terrain.den(side.other))
            if (den >= 0 && Pieces.side(den) == side) return GameStatus.Won(side, WinReason.DEN)
        }
        for (side in Side.entries) if (pos.count(side) == 0) return GameStatus.Won(side.other, WinReason.ALL_CAPTURED)
        if (!hasMove(pos, pos.toMove)) return GameStatus.Won(pos.toMove.other, WinReason.NO_MOVES)
        return GameStatus.Ongoing
    }

    /** Why [from] -> [to] is not a move for the piece on [from] (assumes it is not legal). */
    fun whyIllegal(pos: Position, from: Int, to: Int): Illegal {
        val id = pos.at(from)
        val animal = Pieces.animal(id)
        val side = Pieces.side(id)
        val dc = Sq.col(to) - Sq.col(from)
        val dr = Sq.row(to) - Sq.row(from)
        val straight = (dc == 0) != (dr == 0)
        val adjacent = abs(dc) + abs(dr) == 1
        if (!adjacent) {
            // a lion / tiger aiming across the river in a straight line: the only far target is the far bank
            if (animal.canLeap && straight) {
                val first = Sq.of(Sq.col(from) + Integer.signum(dc), Sq.row(from) + Integer.signum(dr))
                if (Terrain.isRiver(first)) {
                    val target = stepTarget(pos.with(pos.toMove), from, animal, Integer.signum(dc), Integer.signum(dr))
                    if (target < 0 && jumpBlockers(pos, from).isNotEmpty()) return Illegal.RAT_BLOCKS_JUMP
                }
            }
            return Illegal.TOO_FAR
        }
        if (Terrain.denOwner(to) == side) return Illegal.OWN_DEN
        if (Terrain.isRiver(to) && !animal.canSwim) {
            val q = pos.at(to)
            if (q >= 0 && Pieces.side(q) != side && !animal.canLeap) return Illegal.WATER
            if (animal.canLeap) {
                return if (jumpBlockers(pos, from).isNotEmpty()) Illegal.RAT_BLOCKS_JUMP else Illegal.TOO_FAR
            }
            return Illegal.ONLY_RAT_SWIMS
        }
        return captureBlock(pos, from, to) ?: Illegal.TOO_FAR
    }

    /** Rats standing in the river lanes a lion / tiger on [from] would leap: they block the leap (S04 red cross). */
    fun jumpBlockers(pos: Position, from: Int): List<Int> {
        val id = pos.at(from)
        if (id < 0 || !Pieces.animal(id).canLeap) return emptyList()
        val out = ArrayList<Int>()
        for (d in DIRS) {
            var c = Sq.col(from) + d[0]
            var r = Sq.row(from) + d[1]
            while (Sq.valid(c, r) && Terrain.isRiver(Sq.of(c, r))) {
                val sq = Sq.of(c, r)
                val q = pos.at(sq)
                if (q >= 0 && Pieces.animal(q) == Animal.RAT) out += sq
                c += d[0]; r += d[1]
            }
        }
        return out
    }

    /** Pieces of [attacker] that could step into [defender]'s den on their next move (S08). */
    fun denThreats(pos: Position, defender: Side): List<Int> {
        val den = Terrain.den(defender)
        val attacker = defender.other
        val probe = pos.with(attacker)
        val out = ArrayList<Int>()
        for (s in 0 until Sq.COUNT) {
            val id = probe.at(s)
            if (id >= 0 && Pieces.side(id) == attacker && legalMovesFrom(probe, s).any { it.to == den }) out += s
        }
        return out
    }

    /** Number of leaf nodes [depth] plies deep (perft), for the rule tests. */
    fun perft(pos: Position, depth: Int): Long {
        if (depth == 0) return 1
        if (status(pos) != GameStatus.Ongoing) return 1
        var n = 0L
        for (m in allLegalMoves(pos)) {
            val next = apply(pos, m)
            n += if (depth == 1) 1 else perft(next, depth - 1)
        }
        return n
    }
}
