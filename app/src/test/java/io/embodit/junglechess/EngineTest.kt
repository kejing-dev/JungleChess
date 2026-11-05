package io.embodit.junglechess

import io.embodit.junglechess.domain.engine.Engine
import io.embodit.junglechess.domain.model.GameStatus
import io.embodit.junglechess.domain.model.Move
import io.embodit.junglechess.domain.model.Position
import io.embodit.junglechess.domain.model.Side
import io.embodit.junglechess.domain.model.Sq
import io.embodit.junglechess.domain.rules.JungleRules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.random.Random

/**
 * AI strength (design package gameSpec.ai.strengthTest), fixed seeds, sides alternating, design thresholds kept
 * (critic R10: no lowered asserts). A game ends on a win, on the same position a third time (draw, the app's rule)
 * or after 300 plies (800 against the random mover) (undecided). Draws and undecided games count half a point; their share is printed.
 * L1–L4 run at fixed depth; L5 vs L4 runs on the clock scaled to 25 % (2 s -> 500 ms vs 800 -> 200 ms).
 * Game counts are below the design's 100 for unit-test time; printed numbers go into the build hand-off.
 */
class EngineTest {
    data class Tally(val score: Double, val draws: Int, val undecided: Int, val games: Int, val avgPlies: Int) {
        override fun toString() = "score %.2f · draws %d · undecided %d / %d games · avg %d plies".format(score, draws, undecided, games, avgPlies)
    }

    private enum class End { RED, BLUE, DRAW, UNDECIDED }

    private fun play(red: (Position, Map<Position, Int>) -> Move?, blue: (Position, Map<Position, Int>) -> Move?, cap: Int = 300): Pair<End, Int> {
        var p = Position.start()
        val seen = HashMap<Position, Int>().apply { put(p, 1) }
        for (ply in 0 until cap) {
            val st = JungleRules.status(p)
            if (st is GameStatus.Won) return (if (st.winner == Side.RED) End.RED else End.BLUE) to ply
            val m = (if (p.toMove == Side.RED) red(p, seen) else blue(p, seen)) ?: return (if (p.toMove == Side.RED) End.BLUE else End.RED) to ply
            p = JungleRules.apply(p, m)
            val n = (seen[p] ?: 0) + 1
            seen[p] = n
            if (n >= 3) return End.DRAW to ply
        }
        return End.UNDECIDED to cap
    }

    private fun match(hi: (Engine) -> (Position, Map<Position, Int>) -> Move?, lo: (Engine) -> (Position, Map<Position, Int>) -> Move?, games: Int, seed: Long, cap: Int = 300): Tally {
        var score = 0.0; var draws = 0; var und = 0; var plies = 0
        for (g in 0 until games) {
            val h = hi(Engine(seed + g * 2L)); val l = lo(Engine(seed + g * 2L + 1))
            val hiRed = g % 2 == 0
            val (end, n) = if (hiRed) play(h, l, cap) else play(l, h, cap)
            plies += n
            score += when (end) {
                End.DRAW -> { draws++; 0.5 }
                End.UNDECIDED -> { und++; 0.5 }
                End.RED -> if (hiRed) 1.0 else 0.0
                End.BLUE -> if (hiRed) 0.0 else 1.0
            }
        }
        return Tally(score / games, draws, und, games, plies / games)
    }

    private fun fixed(level: Int) = { e: Engine -> { p: Position, s: Map<Position, Int> -> e.choose(p, level, timeLimited = false, repeated = s) } }
    private fun timed(level: Int) = { e: Engine -> { p: Position, s: Map<Position, Int> -> e.choose(p, level, repeated = s, timeScale = 0.25f) } }

    @Test fun takesTheDenWhenItCan() {
        val p = pos("d2=D", "a3=r")
        val m = Engine(1).choose(p, 3)!!
        assertEquals(Sq.parse("d1"), m.to)
    }

    @Test fun beginnerLosesToRandomOftenEnough() {
        // B27: the beginner level must be beatable — against random legal moves it scores <= 60 %
        val r = Random(200L)
        val rnd = { _: Engine -> { p: Position, _: Map<Position, Int> -> JungleRules.allLegalMoves(p).let { if (it.isEmpty()) null else it[r.nextInt(it.size)] } } }
        val t = match(fixed(1), rnd, 100, 100, cap = 800) // random play is slow to finish: longer cap
        println("STRENGTH beginner vs random: $t")
        assertTrue("beginner vs random $t", t.score <= 0.60)
    }

    @Test fun adjacentLevelsAreOrdered() {
        val t21 = match(fixed(2), fixed(1), 30, 10); println("STRENGTH L2 vs L1: $t21")
        val t32 = match(fixed(3), fixed(2), 30, 20); println("STRENGTH L3 vs L2: $t32")
        val t43 = match(fixed(4), fixed(3), 30, 30); println("STRENGTH L4 vs L3: $t43")
        assertTrue("L2 vs L1 $t21", t21.score >= 0.65)
        assertTrue("L3 vs L2 $t32", t32.score >= 0.65)
        assertTrue("L4 vs L3 $t43", t43.score >= 0.65)
    }

    @Test fun masterOnTheClock() {
        val t54 = match(timed(5), timed(4), 10, 40); println("STRENGTH L5 vs L4 (clock x0.25): $t54")
        val t51 = match(timed(5), fixed(1), 10, 50); println("STRENGTH L5 vs L1 (clock x0.25): $t51")
        assertTrue("L5 vs L4 $t54", t54.score >= 0.65)
        assertTrue("L5 vs L1 $t51", t51.score >= 0.95)
    }

    /** critic R1: a cancelled master search stops within 50 ms. */
    @Test fun cancelledSearchStopsFast() {
        val stop = AtomicBoolean(false)
        var finished = 0L
        val e = Engine(3)
        val t = Thread { e.choose(Position.start(), 5, cancel = { stop.get() }, timeLimited = false); finished = System.nanoTime() }
        t.start()
        Thread.sleep(300)
        val cancelledAt = System.nanoTime()
        stop.set(true)
        t.join(2000)
        assertTrue("search still running", !t.isAlive)
        val ms = (finished - cancelledAt) / 1_000_000
        println("CANCEL stopped after $ms ms")
        assertTrue("stopped after $ms ms", ms <= 50)
    }
}
