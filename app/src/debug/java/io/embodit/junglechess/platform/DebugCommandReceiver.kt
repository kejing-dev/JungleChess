package io.embodit.junglechess.platform

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import io.embodit.junglechess.domain.game.Confirm
import io.embodit.junglechess.domain.game.Language
import io.embodit.junglechess.domain.game.SideChoice
import io.embodit.junglechess.domain.game.Tab
import io.embodit.junglechess.domain.model.Position
import io.embodit.junglechess.domain.model.Side
import io.embodit.junglechess.domain.model.Sq
import io.embodit.junglechess.domain.rules.JungleRules
import io.embodit.junglechess.ui.board.DebugHooks
import io.embodit.junglechess.ui.window.WindowCtl

/**
 * Debug channel (debug builds only). Every command goes through the same controller methods as real input.
 *   adb shell am broadcast -a io.embodit.junglechess.DEBUG --es cmd "<command>"
 * tap a9 | move a9 a8 | drag a9 a8 | none | undo | redo | hint on|off | new red|blue|random 1-5 | again | menu
 * tab play|new|rules|settings | rulesdone | continue | confirm resign|restart|exit|none | resign
 * setup <63 chars + ' r|b'> [red|blue] | lang system|zh|en | sound on|off | volume 0-2 | reduced on|off | lit on|off
 * pause on|off (freeze animations for a screenshot) | ftest on|off | light <intensity> | window open|recall
 * state | dump
 */
class DebugCommandReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val raw = intent.getStringExtra("cmd") ?: return
        val graph = Graph.from(context)
        val c = graph.controller
        val a = raw.trim().split(Regex("\\s+"))
        Log.i(TAG, "CMD $raw")
        fun sq(i: Int) = Sq.parse(a.getOrElse(i) { "" }) ?: -1
        fun on(i: Int) = a.getOrNull(i) == "on"
        when (a[0]) {
            "tap" -> c.tap(sq(1))
            "none" -> c.tapNothing()
            "move" -> { c.tap(sq(1)); c.tap(sq(2)) }
            "drag" -> { if (c.dragStart(sq(1))) c.dragEnd(sq(2)) }
            "undo" -> c.undo()
            "redo" -> c.redo()
            "hint" -> c.setHint(on(1))
            "new" -> c.newGame(when (a.getOrNull(1)) { "blue" -> SideChoice.BLUE; "random" -> SideChoice.RANDOM; else -> SideChoice.RED }, a.getOrNull(2)?.toIntOrNull() ?: 1)
            "again" -> c.again()
            "menu" -> c.mainMenu()
            "tab" -> c.setTab(Tab.valueOf(a[1].uppercase()))
            "rulesdone" -> c.rulesDone()
            "continue" -> c.continueSaved()
            "confirm" -> c.askConfirm(when (a.getOrNull(1)) { "resign" -> Confirm.RESIGN; "restart" -> Confirm.RESTART; "exit" -> Confirm.EXIT; else -> null })
            "resign" -> c.resign()
            "setup" -> {
                val text = raw.trim().removePrefix("setup").trim()
                val parts = text.split(" ")
                val pos = Position.decode("${parts[0]} ${parts.getOrElse(1) { "r" }}")
                if (pos != null) c.setup(pos, if (parts.getOrNull(2) == "blue") Side.BLUE else Side.RED) else Log.w(TAG, "setup: bad position")
            }
            "lang" -> c.updateSettings { it.copy(language = Language.valueOf(a[1].uppercase())) }
            "sound" -> c.updateSettings { it.copy(sound = on(1)) }
            "volume" -> c.updateSettings { it.copy(volume = a[1].toInt().coerceIn(0, 2)) }
            "reduced" -> c.updateSettings { it.copy(reducedMotion = on(1)) }
            "lit" -> c.updateSettings { it.copy(litAlways = on(1)) }
            "pause" -> { DebugHooks.scene?.paused = on(1); c.watchdogMs = if (on(1)) 600_000L else 1500L }
            "freeze" -> c.watchdogMs = if (on(1)) 600_000L else 1500L
            "ftest" -> DebugHooks.scene?.showTest = on(1)
            "light" -> DebugHooks.scene?.setLight(a[1].toFloat())
            "window" -> if (a.getOrNull(1) == "recall") WindowCtl.recall() else WindowCtl.openWindow("debug")
            "state" -> {
                val s = c.state.value
                val g = s.game
                Log.i(TAG, "STATE ${s.stateCode} phase=${s.phase} tab=${s.tab} sel=${s.selected} targets=${s.targets.map { Sq.name(it.to) }} line=${s.line} " +
                    "threat=${s.denThreat.map { Sq.name(it) }} hint=${s.hint?.notation()} plies=${g?.plies} pos=${g?.position?.encode()} result=${g?.result} " +
                    "settings=${s.settings} window=${WindowCtl.controlOpen} legal=${g?.let { JungleRules.allLegalMoves(it.position).size }}")
            }
            "dump" -> Log.i(TAG, "DUMP ${DebugHooks.scene?.dump()}")
            else -> Log.w(TAG, "CMD unknown $raw")
        }
    }

    companion object { const val TAG = "JungleChess" }
}
