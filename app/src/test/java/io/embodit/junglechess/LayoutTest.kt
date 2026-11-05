package io.embodit.junglechess

import io.embodit.junglechess.domain.model.Animal
import io.embodit.junglechess.domain.model.Side
import io.embodit.junglechess.domain.model.Sq
import io.embodit.junglechess.ui.board.BoardLayout
import io.embodit.junglechess.ui.board.P3
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Geometry checks of the design package (spatialLayout.occlusionCheck, D16 / D35 / D20 / D41).
 * Boxes are axis-aligned in volume space (conservative for the tilted board).
 */
class LayoutTest {
    /** An axis-aligned box in volume space, or in board space when [tilt] != null (exact for the tilted board). */
    data class Box(val name: String, val min: P3, val max: P3, val tilt: Float? = null) {
        private fun local(p: P3) = if (tilt == null) p else BoardLayout.toBoard(p, tilt)
        fun contains(pv: P3): Boolean { val p = local(pv); return p.x > min.x && p.x < max.x && p.y > min.y && p.y < max.y && p.z > min.z && p.z < max.z }
        /** slab test on the segment a-b */
        fun hits(av: P3, bv: P3): Boolean {
            val a = local(av); val b = local(bv)
            var t0 = 0f; var t1 = 1f
            val d = b - a
            for (k in 0..2) {
                val o = floatArrayOf(a.x, a.y, a.z)[k]; val dd = floatArrayOf(d.x, d.y, d.z)[k]
                val lo = floatArrayOf(min.x, min.y, min.z)[k]; val hi = floatArrayOf(max.x, max.y, max.z)[k]
                if (kotlin.math.abs(dd) < 1e-9f) { if (o <= lo || o >= hi) return false; continue }
                var ta = (lo - o) / dd; var tb = (hi - o) / dd
                if (ta > tb) { val t = ta; ta = tb; tb = t }
                t0 = maxOf(t0, ta); t1 = minOf(t1, tb)
                if (t0 >= t1) return false
            }
            return true
        }
    }

    private val eyes = listOf(P3(0f, 0.32f, 0.85f), P3(0f, 0.70f, 0.85f), P3(-0.425f, 0.32f, 0.736f), P3(0.425f, 0.32f, 0.736f), P3(-0.425f, 0.70f, 0.736f), P3(0.425f, 0.70f, 0.736f))

    /** The board slab and the tallest piece standing (or lifted) on every square, as volume-space boxes. */
    /** The board slab and the tallest piece standing (or lifted) on every square (board space), and the trays. */
    private fun boxes(tilt: Float, lift: Float): List<Box> {
        val out = ArrayList<Box>()
        out += Box("board", P3(-BoardLayout.HALF_W, -BoardLayout.THICK, -BoardLayout.HALF_D), P3(BoardLayout.HALF_W, -0.001f, BoardLayout.HALF_D), tilt) // soil border top (turf tiles are inside the piece boxes)
        for (sq in 0 until Sq.COUNT) {
            val c = BoardLayout.square(sq)
            out += Box("piece ${Sq.name(sq)}", P3(c.x - 0.023f, 0f, c.z - 0.03f), P3(c.x + 0.023f, BoardLayout.TALLEST + lift, c.z + 0.03f), tilt)
        }
        for (side in Side.entries) {
            val t = BoardLayout.trayCenter(side)
            out += Box("tray $side", P3(t.x - 0.07f, t.y, t.z - 0.025f), P3(t.x + 0.07f, t.y + 0.032f, t.z + 0.025f))
        }
        return out
    }

    private fun cardCorners(c: P3, w: Float, h: Float) = listOf(P3(c.x - w / 2, c.y - h / 2, c.z), P3(c.x + w / 2, c.y - h / 2, c.z), P3(c.x - w / 2, c.y + h / 2, c.z), P3(c.x + w / 2, c.y + h / 2, c.z))

    @Test fun cardsAreNeverHiddenByBoardPiecesOrTrays() {
        for (tilt in listOf(0f, 10f, 20f)) for (lift in listOf(0f, BoardLayout.LIFT_SELECTED)) {
            val bx = boxes(tilt, lift)
            val cards = cardCorners(BoardLayout.SEAT_CARD, BoardLayout.SEAT_W, BoardLayout.SEAT_H) +
                Side.entries.flatMap { cardCorners(BoardLayout.countCard(it), BoardLayout.COUNT_W, BoardLayout.COUNT_H) }
            for (e in eyes) for (corner in cards) for (b in bx) {
                assertTrue("tilt $tilt lift $lift: corner $corner inside ${b.name}", !b.contains(corner))
                assertTrue("tilt $tilt lift $lift: eye $e -> $corner crosses ${b.name}", !b.hits(e, corner))
            }
        }
    }

    @Test fun controlWindowAndBoardDoNotOccludeEachOther() {
        // control window right of the volume (design assumption 0.45 x 0.35 m, centre x +0.555), control area inset 32 dp
        // control window right of the volume (design assumption 0.45 x 0.35 m = 560 x 440 dp, centre x +0.555); the page keeps
        // 36 dp (≈ 2.9 cm) free at the bottom: at 32 dp the bottom-left control corner grazes the board edge by 1 mm from the left seat
        val inset = 0.029f
        val win = listOf(P3(0.33f + inset, -0.175f + inset, 0f), P3(0.78f - inset, -0.175f + inset, 0f), P3(0.33f + inset, 0.175f - inset, 0f), P3(0.78f - inset, 0.175f - inset, 0f))
        val bx = boxes(20f, 0f)
        for (e in eyes) for (corner in win) for (b in bx) assertTrue("eye $e -> window $corner crosses ${b.name}", !b.hits(e, corner))
        // D41: from the right seat the board's far right corner stays left of the window edge
        val far = BoardLayout.toVolume(P3(BoardLayout.HALF_W, 0f, -BoardLayout.HALF_D))
        val e = P3(0.425f, 0.32f, 0.736f)
        val t = (0f - e.z) / (far.z - e.z)
        val x = e.x + (far.x - e.x) * t
        assertTrue("board far corner seen at x=$x", x < 0.33f)
    }

    @Test fun everythingStaysInsideTheVolume() {
        // D16: the tallest animal lifted 3 cm (or at the leap apex) on the far row stays under the top
        for (tilt in listOf(0f, 10f, 20f)) {
            for (sq in 0 until Sq.COUNT) {
                val c = BoardLayout.square(sq)
                val top = BoardLayout.toVolume(P3(c.x, BoardLayout.TALLEST + BoardLayout.LIFT_MOVE, c.z), tilt)
                assertTrue("tilt $tilt ${Sq.name(sq)} top ${top.y}", top.y < BoardLayout.VOL_H / 2)
                assertTrue(kotlin.math.abs(top.z) < BoardLayout.VOL_D / 2)
            }
        }
        val near = BoardLayout.toVolume(P3(0f, -BoardLayout.THICK, BoardLayout.HALF_D))
        assertEquals(BoardLayout.FLOOR, near.y, 1e-4f)
        assertTrue(BoardLayout.trayCenter(Side.BLUE).z + BoardLayout.TRAY_D / 2 < BoardLayout.VOL_D / 2)
        assertTrue(BoardLayout.trayCenter(Side.BLUE).x + BoardLayout.TRAY_W / 2 < BoardLayout.VOL_W / 2)
    }

    @Test fun boardTransformRoundTrips() {
        val p = P3(0.1f, 0.02f, -0.2f)
        val back = BoardLayout.toBoard(BoardLayout.toVolume(p))
        assertEquals(p.x, back.x, 1e-5f); assertEquals(p.y, back.y, 1e-5f); assertEquals(p.z, back.z, 1e-5f)
    }

    @Test fun piecesFitTheirSquares() {
        // animals are calibrated to <= 6 cm long (elephant) so neighbours in a column never touch
        for (a in Animal.entries) assertTrue(BoardLayout.animalHeight(a) <= 0.055f)
        assertTrue(2 * BoardLayout.BASE_R < BoardLayout.PITCH)
    }
}
