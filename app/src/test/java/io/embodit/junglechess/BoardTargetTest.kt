package io.embodit.junglechess

import io.embodit.junglechess.domain.model.Animal
import io.embodit.junglechess.domain.model.Pieces
import io.embodit.junglechess.domain.model.Position
import io.embodit.junglechess.domain.model.Sq
import io.embodit.junglechess.ui.board.BoardNames
import io.embodit.junglechess.ui.board.BoardTarget
import io.embodit.junglechess.ui.board.BoardTargets
import io.embodit.junglechess.ui.board.input.parseLoggedPath
import io.embodit.junglechess.ui.board.input.resolvePath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Board-input template must-have test (v0.1.4, after v0.1.3 on the headset: every piece tap logged `-> square=-`).
 * Each kind of hit -- piece collider, any mesh below it, square pad, its visible marker, the board surface -- must
 * resolve to a target and that target to a square, using the names BoardScene really sets.
 */
class BoardTargetTest {
    private val start = Position.start()
    private fun square(chain: List<String>, pos: Position = start, board: Int = -1) =
        BoardTargets.squareFor(resolvePath(chain, BoardTargets::resolveName), pos) { board }

    @Test fun everyPieceMeshResolvesToItsSquare() {
        for (id in 0 until Pieces.COUNT) {
            val want = start.squareOf(id)
            check(want >= 0)
            val a = Pieces.animal(id).asset
            val chains = listOf(
                listOf(BoardNames.hit(id), BoardNames.piece(id)), // collider itself
                listOf("Mesh_0", "animal:$id:$a", BoardNames.vis(id), BoardNames.hit(id), BoardNames.piece(id)), // animal sub-mesh
                listOf("BaseRing", "base:$id", BoardNames.vis(id), BoardNames.hit(id), BoardNames.piece(id)), // base sub-mesh
                listOf("tag:$id", BoardNames.piece(id)), // rank tag on the holder
            )
            for (c in chains) assertEquals("piece $id via $c", want, square(c))
        }
    }

    @Test fun pieceFollowsTheBoardNotItsStartSquare() {
        val rat = Pieces.id(io.embodit.junglechess.domain.model.Side.RED, Animal.RAT)
        val cells = start.cellsCopy()
        val from = start.squareOf(rat)
        val to = (0 until Sq.COUNT).first { cells[it] == 0 && it != from }
        cells[from] = 0; cells[to] = rat + 1
        val moved = Position(cells, start.toMove)
        assertEquals(to, square(listOf(BoardNames.hit(rat)), moved))
    }

    @Test fun everySquarePadAndMarkerResolves() {
        for (sq in 0 until Sq.COUNT) {
            assertEquals(sq, square(listOf(BoardNames.square(sq))))
            assertEquals(sq, square(listOf(BoardNames.dot(sq), BoardNames.square(sq), "boardRoot")))
        }
    }

    @Test fun boardSurfaceAsksTheHitPoint() {
        assertEquals(BoardTarget.Board, resolvePath(listOf("Turf_3", BoardNames.TERRAIN), BoardTargets::resolveName))
        assertEquals(30, square(listOf("Turf_3", BoardNames.TERRAIN), board = 30))
    }

    /** Replays real v0.1.3 headset log paths: the bad names must not pass as a piece; v0.1.4 names must. */
    @Test fun replayHeadsetLogPaths() {
        // 11-06 02:18:16 `BoardInput: TAP src=hand-ray hit=Piece(id=122) path=hit:122` -> square=- (the bug)
        assertNull(resolvePath(parseLoggedPath("hit:122"), BoardTargets::resolveName))
        // 11-06 02:18:28 `TAP src=hand-ray hit=Square(sq=45) path=sq:45` -> d7
        assertEquals(45, square(parseLoggedPath("sq:45")))
        // what the same piece tap logs from v0.1.4 on (deepest mesh up to the holder)
        val lion = Pieces.id(io.embodit.junglechess.domain.model.Side.RED, Animal.LION)
        assertEquals(start.squareOf(lion), square(parseLoggedPath("Mesh_2 < animal:$lion:lion < vis:$lion < hit:$lion < piece:$lion")))
    }

    @Test fun nothingOursResolvesToNothing() {
        assertEquals(-1, square(listOf("keyLight", "root")))
        assertEquals(-1, square(emptyList()))
    }
}
