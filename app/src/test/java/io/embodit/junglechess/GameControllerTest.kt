package io.embodit.junglechess

import io.embodit.junglechess.domain.game.GameCodec
import io.embodit.junglechess.domain.game.GameController
import io.embodit.junglechess.domain.game.GameStore
import io.embodit.junglechess.domain.game.LineKey
import io.embodit.junglechess.domain.game.Phase
import io.embodit.junglechess.domain.game.Settings
import io.embodit.junglechess.domain.game.SideChoice
import io.embodit.junglechess.domain.game.Tab
import io.embodit.junglechess.domain.model.Position
import io.embodit.junglechess.domain.model.Side
import io.embodit.junglechess.domain.model.Sq
import io.embodit.junglechess.domain.rules.Illegal
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FakeStore(var game: String? = null, var settings: Settings = Settings(firstRunDone = true)) : GameStore {
    override fun loadGame() = game
    override fun saveGame(text: String?) { game = text }
    override fun loadSettings() = settings
    override fun saveSettings(settings: Settings) { this.settings = settings }
}

@OptIn(ExperimentalCoroutinesApi::class)
class GameControllerTest {
    private val dispatcher = StandardTestDispatcher()
    private val scope = TestScope(dispatcher)
    private fun controller(store: FakeStore = FakeStore()) =
        GameController(scope, dispatcher, store, now = { scope.testScheduler.currentTime }, seed = 3).also { it.start() }

    private fun GameController.finishAnim() { state.value.anim?.let { animationDone(it.token) } }

    @Test fun firstRunOpensRules() {
        val c = controller(FakeStore(settings = Settings()))
        assertEquals(Tab.RULES, c.state.value.tab)
        assertEquals("S01", c.state.value.stateCode)
        c.rulesDone()
        assertEquals(Tab.NEW, c.state.value.tab)
    }

    @Test fun selectMoveThenAiReplies() = scope.run {
        val c = controller()
        c.newGame(SideChoice.RED, 1)
        assertEquals(Phase.IDLE, c.state.value.phase)
        assertTrue("first game lights movable pieces", c.state.value.lit.isNotEmpty())
        c.tap(Sq.parse("g7")!!) // red rat
        assertEquals(Phase.SELECTED, c.state.value.phase)
        assertEquals(LineKey.ANY_LIT, c.state.value.line.key)
        c.tap(Sq.parse("g6")!!) // next to the river
        assertEquals(Phase.ANIMATING, c.state.value.phase)
        assertNotNull("committed and saved before the animation", c.state.value.game?.lastMove)
        c.finishAnim()
        assertEquals(Phase.THINKING, c.state.value.phase)
        advanceUntilIdle()
        // AI move is committed; its animation then times out through the watchdog
        assertEquals(Phase.IDLE, c.state.value.phase)
        assertEquals(2, c.state.value.game!!.plies)
    }

    @Test fun illegalTapFlashesWithReason() = scope.run {
        val c = controller()
        c.newGame(SideChoice.RED, 1)
        c.tap(Sq.parse("a7")!!) // elephant
        c.tap(Sq.parse("a5")!!) // two squares away
        val s = c.state.value
        assertNotNull(s.flash)
        assertEquals(Illegal.TOO_FAR, s.line.illegal)
        assertEquals(Phase.SELECTED, s.phase)
        advanceUntilIdle()
        assertNull(c.state.value.flash)
    }

    @Test fun undoTakesBackAWholeRound() = scope.run {
        val c = controller()
        c.newGame(SideChoice.RED, 1)
        val start = c.state.value.game!!.position
        c.tap(Sq.parse("g7")!!); c.tap(Sq.parse("g6")!!); c.finishAnim()
        advanceUntilIdle()
        assertEquals(2, c.state.value.game!!.plies)
        c.undo()
        assertEquals(start, c.state.value.game!!.position)
        assertEquals(LineKey.UNDONE, c.state.value.line.key)
        c.redo()
        assertEquals(2, c.state.value.game!!.plies)
    }

    @Test fun undoWhileAiThinksCancelsAndTakesBackOneMove() = scope.run {
        val c = controller()
        c.newGame(SideChoice.RED, 5)
        val start = c.state.value.game!!.position
        c.tap(Sq.parse("g7")!!); c.tap(Sq.parse("g6")!!); c.finishAnim()
        assertEquals(Phase.THINKING, c.state.value.phase)
        c.undo()
        advanceUntilIdle()
        assertEquals(start, c.state.value.game!!.position)
        assertEquals(Phase.IDLE, c.state.value.phase)
    }

    @Test fun restoreRecomputesAndResumesAi() = scope.run {
        val store = FakeStore()
        val c = controller(store)
        c.newGame(SideChoice.BLUE, 1) // AI (red) moves first
        assertEquals(Phase.THINKING, c.state.value.phase)
        // process killed while the AI thinks: a new controller restores and searches again
        val c2 = controller(store)
        assertEquals(Phase.THINKING, c2.state.value.phase)
        assertTrue(c2.state.value.restored || c2.state.value.line.key == LineKey.RESTORED)
        advanceUntilIdle()
        assertEquals(1, c2.state.value.game!!.plies)
    }

    @Test fun denAlertAndResult() = scope.run {
        val c = controller()
        c.newGame(SideChoice.RED, 1)
        // blue dog one step from red's den, red to move
        c.setup(pos("d8=d", "a5=R", "a1=l"), Side.RED, 1)
        assertEquals("S08", c.state.value.stateCode)
        assertEquals(LineKey.DEN_ALERT, c.state.value.line.key)
        // red walks into the blue den: win
        c.setup(pos("d2=D", "a3=r"), Side.RED, 1)
        c.tap(Sq.parse("d2")!!); c.tap(Sq.parse("d1")!!); c.finishAnim()
        assertEquals(Phase.RESULT, c.state.value.phase)
        assertEquals(Side.RED, c.state.value.game!!.result!!.winner)
    }

    @Test fun saveRoundTrip() = scope.run {
        val store = FakeStore()
        val c = controller(store)
        c.newGame(SideChoice.RED, 3)
        c.tap(Sq.parse("g7")!!); c.tap(Sq.parse("g6")!!)
        val (g, hist) = GameCodec.decode(store.game!!)!!
        assertEquals(c.state.value.game!!.position, g.position)
        assertEquals(Position.start(), hist.first().position)
    }

    @Test fun settingsTemporaryChangesStayInSettingsOnly() = scope.run {
        val store = FakeStore()
        val c = controller(store)
        c.updateSettings { it.copy(reducedMotion = true) }
        c.newGame(SideChoice.RED, 1)
        c.tap(Sq.parse("g7")!!); c.tap(Sq.parse("g6")!!)
        assertTrue(c.state.value.anim!!.reduced)
        assertEquals(true, store.settings.reducedMotion)
    }
}

/** critic R1 on real threads: a new game while the master searches stops that search within 50 ms. */
class SearchCancelTest {
    @org.junit.Test fun newGameStopsTheRunningSearch() = kotlinx.coroutines.runBlocking {
        val main = kotlinx.coroutines.Dispatchers.Default.limitedParallelism(1)
        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + main)
        val c = GameController(scope, kotlinx.coroutines.Dispatchers.Default, FakeStore(), now = { System.currentTimeMillis() }, seed = 3)
        kotlinx.coroutines.withContext(main) { c.start(); c.newGame(SideChoice.BLUE, 5) } // AI (red) starts a 2 s search
        kotlinx.coroutines.delay(300)
        org.junit.Assert.assertEquals(1, c.runningSearches.get())
        kotlinx.coroutines.withContext(main) { c.newGame(SideChoice.RED, 5) } // human first: no new search
        kotlinx.coroutines.delay(50)
        org.junit.Assert.assertEquals("old search still running 50 ms after the new game", 0, c.runningSearches.get())
        scope.coroutineContext[kotlinx.coroutines.Job]!!.cancel()
    }
}
