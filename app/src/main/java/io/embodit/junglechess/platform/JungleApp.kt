package io.embodit.junglechess.platform

import android.app.Application
import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.pico.spatial.ui.foundation.dsl.launch
import io.embodit.junglechess.data.AndroidGameStore
import io.embodit.junglechess.domain.game.GameController
import io.embodit.junglechess.mainApp
import io.embodit.junglechess.ui.window.WindowCtl
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class JungleApp : Application() {
    lateinit var graph: Graph
        private set

    override fun onCreate() {
        super.onCreate()
        graph = Graph(this)
        launch(::mainApp)
    }
}

/**
 * Process-wide objects shared by the board volume and the control window (both read [controller]'s state).
 */
class Graph(context: Context) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val sounds = SoundPlayer(context)
    val store = AndroidGameStore(context)
    val controller = GameController(
        scope = scope,
        compute = Dispatchers.Default,
        store = store,
        now = { SystemClock.elapsedRealtime() },
        log = { Log.i(TAG, it) },
        cue = { c, a -> sounds.play(c, a) },
    )
    val window = WindowCtl

    init {
        controller.start()
        scope.launch {
            controller.state.collect { sounds.enabled = it.settings.sound; sounds.volume = it.settings.volume }
        }
    }

    companion object {
        const val TAG = "JungleChess"
        fun from(context: Context): Graph = (context.applicationContext as JungleApp).graph
    }
}
