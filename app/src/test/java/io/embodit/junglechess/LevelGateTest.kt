package io.embodit.junglechess

import io.embodit.junglechess.ui.window.LevelGate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LevelGateTest {
    @Test fun opensOnlyWhenLevelForDwell() {
        val g = LevelGate()
        g.ask("launch", 0)
        assertNull(g.step(0, 30f)); assertNull(g.step(100, 2f)); assertNull(g.step(300, 2f))
        assertEquals("launch", g.step(401, 2f))
    }
    @Test fun noHeadPoseFallsBackAfterTimeout() {
        val g = LevelGate(noHeadMs = 600)
        g.ask("launch", 0)
        assertNull(g.step(10, null)); assertNull(g.step(500, null))
        assertEquals("launch/nohead", g.step(611, null))
    }
    @Test fun tiltedHeadKeepsWaiting() {
        val g = LevelGate()
        g.ask("recall", 0)
        for (t in 0..10_000 step 100) assertNull(g.step(t.toLong(), -30f))
    }
}
