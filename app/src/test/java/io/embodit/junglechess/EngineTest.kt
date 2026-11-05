package io.embodit.junglechess

import io.embodit.junglechess.domain.engine.Engine
import io.embodit.junglechess.domain.model.GameStatus
import io.embodit.junglechess.domain.model.Position
import io.embodit.junglechess.domain.model.Side
import io.embodit.junglechess.domain.model.Sq
import io.embodit.junglechess.domain.rules.JungleRules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * AI strength (design package gameSpec.ai.strengthTest), run at fixed depth (no clock) with fixed seeds.
 * Game counts are reduced from the design's 100 to keep the unit run short; the numbers are printed and
 * recorded in the build hand-off. A game is capped at 160 plies (counted as half a point each).
 */
class EngineTest {
    private fun play(red: (Position) -> io.embodit.junglechess.domain.model.Move?, blue: (Position) -> io.embodit.junglechess.domain.model.Move?): Side? {
        var p = Position.start()
        repeat(160) {
            val st = JungleRules.status(p)
            if (st is GameStatus.Won) return st.winner
            val m = (if (p.toMove == Side.RED) red(p) else blue(p)) ?: return p.toMove.other
            p = JungleRules.apply(p, m)
        }
        return (JungleRules.status(p) as? GameStatus.Won)?.winner
    }

    /** Score of [hi] against [lo] over [games] games, sides alternating. */
    private fun match(hi: Int, lo: Int, games: Int, seed: Long): Double {
        var score = 0.0
        for (g in 0 until games) {
            val eh = Engine(seed + g * 2L); val el = Engine(seed + g * 2L + 1)
            val hiMove = { p: Position -> eh.choose(p, hi, timeLimited = false) }
            val loMove = { p: Position -> el.choose(p, lo, timeLimited = false) }
            val hiRed = g % 2 == 0
            val w = if (hiRed) play(hiMove, loMove) else play(loMove, hiMove)
            score += when (w) { null -> 0.5; Side.RED -> if (hiRed) 1.0 else 0.0; Side.BLUE -> if (hiRed) 0.0 else 1.0 }
        }
        return score / games
    }

    @Test fun takesTheDenWhenItCan() {
        val p = pos("d2=D", "a3=r")
        val m = Engine(1).choose(p, 3)!!
        assertEquals(Sq.parse("d1"), m.to)
    }

    @Test fun beginnerLosesToRandomOftenEnough() {
        // B27: the beginner level must be beatable — against random legal moves it scores <= 60 %
        var score = 0.0
        val games = 100
        for (g in 0 until games) {
            val e = Engine(100L + g); val r = Random(200L + g)
            val ai = { p: Position -> e.choose(p, 1, timeLimited = false) }
            val rnd = { p: Position -> JungleRules.allLegalMoves(p).let { if (it.isEmpty()) null else it[r.nextInt(it.size)] } }
            val aiRed = g % 2 == 0
            val w = if (aiRed) play(ai, rnd) else play(rnd, ai)
            score += when (w) { null -> 0.5; Side.RED -> if (aiRed) 1.0 else 0.0; Side.BLUE -> if (aiRed) 0.0 else 1.0 }
        }
        println("STRENGTH beginner vs random: ${score / games}")
        assertTrue("beginner vs random ${score / games}", score / games <= 0.60)
    }

    @Test fun higherLevelsWin() {
        val r21 = match(2, 1, 20, 10); println("STRENGTH L2 vs L1: $r21")
        val r32 = match(3, 2, 20, 20); println("STRENGTH L3 vs L2: $r32")
        val r43 = match(4, 3, 10, 30); println("STRENGTH L4 vs L3: $r43")
        assertTrue("L2 vs L1 $r21", r21 >= 0.65)
        assertTrue("L3 vs L2 $r32", r32 >= 0.65)
        assertTrue("L4 vs L3 $r43", r43 >= 0.5)
    }
}
