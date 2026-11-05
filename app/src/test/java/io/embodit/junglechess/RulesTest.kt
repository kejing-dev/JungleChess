package io.embodit.junglechess

import io.embodit.junglechess.domain.model.Animal
import io.embodit.junglechess.domain.model.GameStatus
import io.embodit.junglechess.domain.model.Pieces
import io.embodit.junglechess.domain.model.Position
import io.embodit.junglechess.domain.model.Side
import io.embodit.junglechess.domain.model.Sq
import io.embodit.junglechess.domain.model.WinReason
import io.embodit.junglechess.domain.rules.Illegal
import io.embodit.junglechess.domain.rules.JungleRules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Builds positions from "a9=T" style placements; upper case = red, lower = blue. */
fun pos(vararg placements: String, toMove: Side = Side.RED): Position {
    val chars = CharArray(Sq.COUNT) { '.' }
    for (p in placements) { val (sq, c) = p.split("="); chars[Sq.parse(sq)!!] = c[0] }
    return Position.decode(String(chars) + " " + if (toMove == Side.RED) "r" else "b")!!
}

class RulesTest {
    private fun can(p: Position, from: String, to: String) = JungleRules.find(p, Sq.parse(from)!!, Sq.parse(to)!!) != null

    // ---- independent move counter (does not share code with JungleRules) for perft cross-checks ----
    private fun naiveMoves(p: Position): Int {
        var n = 0
        val river = { c: Int, r: Int -> r in 4..6 && (c in 1..2 || c in 4..5) }
        for (s in 0 until Sq.COUNT) {
            val id = p.at(s); if (id < 0 || Pieces.side(id) != p.toMove) continue
            val a = Pieces.animal(id); val side = Pieces.side(id)
            for ((dc, dr) in listOf(1 to 0, -1 to 0, 0 to 1, 0 to -1)) {
                var c = Sq.col(s) + dc; var r = Sq.row(s) + dr
                if (c !in 0..6 || r !in 1..9) continue
                if (river(c, r) && a != Animal.RAT) {
                    if (a != Animal.LION && a != Animal.TIGER) continue
                    var blocked = false
                    while (c in 0..6 && r in 1..9 && river(c, r)) { val q = p.at(Sq.of(c, r)); if (q >= 0 && Pieces.animal(q) == Animal.RAT) blocked = true; c += dc; r += dr }
                    if (blocked || c !in 0..6 || r !in 1..9) continue
                }
                val to = Sq.of(c, r)
                if ((side == Side.RED && to == Sq.of(3, 9)) || (side == Side.BLUE && to == Sq.of(3, 1))) continue
                val q = p.at(to)
                if (q >= 0) {
                    if (Pieces.side(q) == side) continue
                    val ar = river(Sq.col(s), Sq.row(s)); val dr2 = river(c, r)
                    if (ar != dr2) continue
                    val traps = if (side == Side.RED) listOf("c1", "e1", "d2") else listOf("c9", "e9", "d8")
                    val trapped = traps.any { Sq.parse(it) == to }
                    val da = Pieces.animal(q)
                    val ok = trapped || (a == Animal.RAT && da == Animal.ELEPHANT) || (!(a == Animal.ELEPHANT && da == Animal.RAT) && a.rank >= da.rank)
                    if (!ok) continue
                }
                n++
            }
        }
        return n
    }

    private fun naivePerft(p: Position, depth: Int): Long {
        if (depth == 0 || JungleRules.status(p) != GameStatus.Ongoing) return 1
        if (depth == 1) return naiveMoves(p).toLong()
        return JungleRules.allLegalMoves(p).sumOf { naivePerft(JungleRules.apply(p, it), depth - 1) }
    }

    @Test fun perftDepth1Is24() = assertEquals(24L, JungleRules.perft(Position.start(), 1))
    @Test fun perftDepth2And3MatchIndependentCounter() {
        assertEquals(naivePerft(Position.start(), 2), JungleRules.perft(Position.start(), 2))
        assertEquals(naivePerft(Position.start(), 3), JungleRules.perft(Position.start(), 3))
        assertEquals(24L * 24L, JungleRules.perft(Position.start(), 2))
    }

    @Test fun sameRankCaptures() = assertTrue(can(pos("d5=W", "d4=w"), "d5", "d4"))
    @Test fun ratCapturesElephant() = assertTrue(can(pos("a5=R", "a4=e"), "a5", "a4"))
    @Test fun elephantCannotCaptureRat() {
        val p = pos("a5=E", "a4=r")
        assertFalse(can(p, "a5", "a4"))
        assertEquals(Illegal.ELEPHANT_RAT, JungleRules.whyIllegal(p, Sq.parse("a5")!!, Sq.parse("a4")!!))
    }
    @Test fun ratInRiverCannotCaptureOnLand() = assertFalse(can(pos("b4=R", "a4=e"), "b4", "a4"))
    @Test fun landCannotCaptureRatInRiver() {
        val p = pos("a4=C", "b4=r")
        assertFalse(can(p, "a4", "b4"))
        assertEquals(Illegal.WATER, JungleRules.whyIllegal(p, Sq.parse("a4")!!, Sq.parse("b4")!!))
    }
    @Test fun riverRatCapturesRiverRat() = assertTrue(can(pos("b4=R", "b5=r"), "b4", "b5"))
    @Test fun lionLeapsAcrossAndAlong() {
        val p = pos("a5=L")
        assertTrue(can(p, "a5", "d5"))
        val q = pos("b7=L")
        assertTrue(can(q, "b7", "b3"))
    }
    @Test fun tigerLeapBlockedByOwnRat() {
        val p = pos("b7=T", "b5=R")
        assertFalse(can(p, "b7", "b3"))
        assertEquals(Illegal.RAT_BLOCKS_JUMP, JungleRules.whyIllegal(p, Sq.parse("b7")!!, Sq.parse("b3")!!))
        assertEquals(listOf(Sq.parse("b5")!!), JungleRules.jumpBlockers(p, Sq.parse("b7")!!))
    }
    @Test fun leapBlockedByEnemyRat() = assertFalse(can(pos("a5=L", "c5=r"), "a5", "d5"))
    @Test fun leapCapturesOnFarBank() = assertTrue(can(pos("a5=L", "d5=p"), "a5", "d5"))
    @Test fun leapOntoOwnPieceNotAllowed() = assertFalse(can(pos("a5=L", "d5=P"), "a5", "d5"))
    @Test fun leapOntoBiggerPieceNotAllowed() = assertFalse(can(pos("a5=T", "d5=e"), "a5", "d5"))
    @Test fun nonRatCannotSwim() {
        val p = pos("a4=D")
        assertFalse(can(p, "a4", "b4"))
        assertEquals(Illegal.ONLY_RAT_SWIMS, JungleRules.whyIllegal(p, Sq.parse("a4")!!, Sq.parse("b4")!!))
        assertTrue(can(pos("a4=R"), "a4", "b4"))
    }
    @Test fun cannotEnterOwnDen() {
        val p = pos("d8=D")
        assertFalse(can(p, "d8", "d9"))
        assertEquals(Illegal.OWN_DEN, JungleRules.whyIllegal(p, Sq.parse("d8")!!, Sq.parse("d9")!!))
    }
    @Test fun pieceInEnemyTrapIsRankZero() {
        // blue elephant in red's trap c9 can be taken by the red cat
        assertTrue(can(pos("b9=C", "c9=e", "a1=l"), "b9", "c9"))
        // but out of the trap it cannot
        assertFalse(can(pos("b8=C", "c8=e", "a1=l"), "b8", "c8"))
        // own trap gives no protection change: a red elephant in its own trap still beats a blue cat
        assertFalse(can(pos("b9=c", "c9=E", "a1=L", toMove = Side.BLUE), "b9", "c9"))
    }
    @Test fun leavingTrapRestoresRank() {
        val p = pos("c9=e", "c7=C", "a1=l", toMove = Side.BLUE)
        val m = JungleRules.find(p, Sq.parse("c9")!!, Sq.parse("c8")!!)!!
        val q = JungleRules.apply(p, m)
        assertFalse(can(q, "c7", "c8"))
    }
    @Test fun denWins() {
        val p = pos("d2=D", "a3=r")
        val after = JungleRules.apply(p, JungleRules.find(p, Sq.parse("d2")!!, Sq.parse("d1")!!)!!)
        assertEquals(GameStatus.Won(Side.RED, WinReason.DEN), JungleRules.status(after))
    }
    @Test fun capturingEverythingWins() {
        val p = pos("d5=W", "d4=w")
        val after = JungleRules.apply(p, JungleRules.find(p, Sq.parse("d5")!!, Sq.parse("d4")!!)!!)
        assertEquals(GameStatus.Won(Side.RED, WinReason.ALL_CAPTURED), JungleRules.status(after))
    }
    @Test fun noMovesLoses() {
        // blue rat boxed in the corner by red elephants it cannot reach? rat can take elephants, so box with cats
        val p = pos("a1=r", "b1=C", "a2=D", "g9=L", toMove = Side.BLUE)
        // rat (1) cannot take the cat (2) or the dog (3): no legal move
        assertTrue(JungleRules.allLegalMoves(p).isEmpty())
        assertEquals(GameStatus.Won(Side.RED, WinReason.NO_MOVES), JungleRules.status(p))
    }
    @Test fun denThreatDetected() {
        val p = pos("d8=d", "a5=R", toMove = Side.RED)
        assertEquals(listOf(Sq.parse("d8")!!), JungleRules.denThreats(p, Side.RED))
    }
    @Test fun encodeRoundTrip() {
        val s = Position.start()
        assertEquals(s, Position.decode(s.encode()))
        assertNull(Position.decode("bad"))
        assertNotNull(JungleRules.allLegalMoves(s))
    }
}
