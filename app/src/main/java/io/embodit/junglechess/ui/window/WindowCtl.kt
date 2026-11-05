package io.embodit.junglechess.ui.window

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.pico.spatial.ui.platform.containers.SpatialNavigator

/**
 * The control window's lifecycle, process-wide (both containers see it). Every open goes through [LevelGate];
 * the board's frame system calls [stepGate] each frame. A watchdog opens anyway when the board renders no
 * frames (not up yet / background), so the window can never get stuck closed.
 */
object WindowCtl {
    const val BOARD = "JungleBoard"
    const val CONTROL = "JungleControl"
    const val CONTROL_TAG = "control"
    private const val TAG = "JungleChess"

    /** Shared Space has no head pose: the no-head fallback is short so the panel is there at launch. */
    private val gate = LevelGate(noHeadMs = 600L)
    private val main = Handler(Looper.getMainLooper())
    private var navigator: SpatialNavigator? = null
    private var lastStepMs = 0L
    private var reopenAfterClose = false

    /** True while the control window is composed (ON_CREATE .. ON_DESTROY). */
    var controlOpen by mutableStateOf(false)
        private set

    /** Set by the board when it starts; the navigator is what opens / closes windows. */
    fun attach(nav: SpatialNavigator) { navigator = nav }

    fun detach(nav: SpatialNavigator) { if (navigator === nav) navigator = null; gate.cancel() }

    /** Asks for the control window (launch, reopen after ×, recall). */
    fun openWindow(why: String) {
        if (controlOpen) return
        gate.ask(why, SystemClock.elapsedRealtime())
        Log.i(TAG, "WINDOW ask why=$why")
        main.removeCallbacks(watchdog)
        main.postDelayed(watchdog, 3000)
    }

    /** Called by the board every frame with the head pitch (null: unknown — always, in Shared Space). */
    fun stepGate(now: Long, pitchDeg: Float?) {
        lastStepMs = now
        gate.step(now, pitchDeg)?.let { openNow(it) }
    }

    private val watchdog = object : Runnable {
        override fun run() {
            if (!gate.waiting) return
            val now = SystemClock.elapsedRealtime()
            val stalled = if (lastStepMs == 0L) gate.waitedMs(now) > 3000 else now - lastStepMs > 3000
            if (stalled) { val why = gate.pending!!; gate.cancel(); openNow("$why/noframes") } else main.postDelayed(this, 3000)
        }
    }

    private fun openNow(why: String) {
        val nav = navigator ?: return
        Log.i(TAG, "WINDOW open why=$why")
        runCatching { nav.openWindowContainer(CONTROL, CONTROL_TAG) }.onFailure { Log.e(TAG, "WINDOW open failed", it) }
    }

    /** Settings [叫回面前]: close, and open again once the window is gone (an open right after close is swallowed). */
    fun recall() {
        val nav = navigator ?: return
        Log.i(TAG, "WINDOW recall")
        reopenAfterClose = true
        runCatching { nav.closeWindowContainer(CONTROL, CONTROL_TAG) }
    }

    fun closeAll() {
        gate.cancel()
        runCatching { navigator?.closeWindowContainer(CONTROL, CONTROL_TAG) }
    }

    fun onControlCreated() { controlOpen = true; Log.i(TAG, "WINDOW created") }

    fun onControlDestroyed() {
        controlOpen = false
        Log.i(TAG, "WINDOW destroyed reopen=$reopenAfterClose")
        if (reopenAfterClose) {
            reopenAfterClose = false
            main.postDelayed({ openWindow("recall") }, 250)
        }
    }
}
