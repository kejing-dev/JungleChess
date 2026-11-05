package io.embodit.junglechess.domain.game

import io.embodit.junglechess.domain.model.Animal
import io.embodit.junglechess.domain.model.Move
import io.embodit.junglechess.domain.model.Pieces
import io.embodit.junglechess.domain.model.Position
import io.embodit.junglechess.domain.model.Side
import io.embodit.junglechess.domain.model.Sq
import io.embodit.junglechess.domain.model.WinReason
import io.embodit.junglechess.domain.rules.Illegal

/** Control window pages (system TabBar, D8). */
enum class Tab { PLAY, NEW, RULES, SETTINGS }

/** Named states of the design package (states[]); v1 implements S00–S06, S08–S13, S16, S17. */
enum class Phase(val code: String) {
    LOADING("S00"),
    SETUP("S02"),
    IDLE("S03"),
    SELECTED("S04"),
    ANIMATING("S05"),
    THINKING("S06"),
    RESULT("S09"),
}

enum class Language { SYSTEM, ZH, EN }

data class Settings(
    val language: Language = Language.SYSTEM,
    val sound: Boolean = true,
    /** 0 soft, 1 medium, 2 loud (three-step slider). */
    val volume: Int = 1,
    val reducedMotion: Boolean = false,
    /** Lit movable pieces in every game (default: first game, three moves). */
    val litAlways: Boolean = false,
    val firstRunDone: Boolean = false,
    val gamesStarted: Int = 0,
)

data class GameResult(val winner: Side, val reason: WinReason, val piece: Int = -1)

/** One game in progress (or finished). Everything the save file needs. */
data class Game(
    val position: Position,
    val human: Side,
    val level: Int,
    val plies: Int = 0,
    /** Captured piece ids in the order they were taken. */
    val captured: List<Int> = emptyList(),
    val lastMove: Move? = null,
    val result: GameResult? = null,
    /** Human moves left with the "lit movable pieces" help (first game). */
    val litLeft: Int = 0,
    val elapsedMs: Long = 0,
) {
    val ai: Side get() = human.other
    fun capturedBy(side: Side): List<Int> = captured.filter { Pieces.side(it) != side }
}

/** Which sentence the status line shows; rendered in both languages by the UI (ui/i18n/Texts.kt). */
enum class LineKey {
    PICK_ONE, YOUR_MOVE, CAN_JUMP, RAT_SWIM, CAN_CAPTURE, ANY_LIT, RAT_BLOCKS, RAT_SAFE_IN_RIVER,
    ILLEGAL, NOT_YOURS, CANT_MOVE, CANCELLED,
    THINKING, JUMPED, CAPTURED, TOPPLED, MOVED, THEY_CAPTURED, DEN_ALERT,
    RESTORED, UNDONE, REDONE, NOTHING_TO_UNDO, HINT_ON, HINT_OFF, NEW_GAME, RESULT,
}

data class Line(val key: LineKey, val animal: Animal? = null, val other: Animal? = null, val illegal: Illegal? = null)

/** A committed move the board animates (S05 / S17). [token] is unique per animation. */
data class Anim(val token: Int, val move: Move, val before: Position, val after: Position, val reduced: Boolean)

/** A red frame on [square] for 300 ms with the reason in the status line (T2). */
data class Flash(val square: Int, val token: Int)

enum class Confirm { RESIGN, RESTART, EXIT }

data class UiState(
    val phase: Phase = Phase.LOADING,
    val tab: Tab = Tab.PLAY,
    val game: Game? = null,
    val selected: Int = -1,
    val targets: List<Move> = emptyList(),
    val blockers: List<Int> = emptyList(),
    val flash: Flash? = null,
    val hintOn: Boolean = false,
    val hint: Move? = null,
    val line: Line = Line(LineKey.PICK_ONE),
    val denThreat: List<Int> = emptyList(),
    val anim: Anim? = null,
    val restored: Boolean = false,
    val confirm: Confirm? = null,
    val savedGame: Game? = null,
    val settings: Settings = Settings(),
    val lit: List<Int> = emptyList(),
    val modelsReady: Boolean = false,
    val modelsFailed: Boolean = false,
    /** New-game page draft. */
    val draftSide: SideChoice = SideChoice.RED,
    val draftLevel: Int = 1,
) {
    val humanToMove: Boolean get() = game?.let { it.result == null && it.position.toMove == it.human } ?: false
    val stateCode: String get() = when {
        phase == Phase.ANIMATING && anim?.move?.isJump == true -> "S17"
        phase == Phase.IDLE && denThreat.isNotEmpty() -> "S08"
        phase == Phase.IDLE && restored -> "S16"
        confirm != null -> "S13"
        tab == Tab.SETTINGS -> "S11"
        tab == Tab.RULES -> if (!settings.firstRunDone) "S01" else "S12"
        tab == Tab.NEW && phase != Phase.LOADING -> "S02"
        else -> phase.code
    }
}

enum class SideChoice { RED, BLUE, RANDOM }

/** Text form of a game for the save file (one key per line; see [GameCodec]). */
object GameCodec {
    fun encode(game: Game, history: List<Game>): String = buildString {
        append("jungle-save v1\n")
        append("game=").append(one(game)).append('\n')
        for (h in history.takeLast(MAX_HISTORY)) append("hist=").append(one(h)).append('\n')
    }

    fun decode(text: String): Pair<Game, List<Game>>? = runCatching {
        val lines = text.lines()
        if (lines.firstOrNull() != "jungle-save v1") return null
        val game = lines.firstOrNull { it.startsWith("game=") }?.removePrefix("game=")?.let(::parseOne) ?: return null
        val hist = lines.filter { it.startsWith("hist=") }.mapNotNull { parseOne(it.removePrefix("hist=")) }
        game to hist
    }.getOrNull()

    const val MAX_HISTORY = 120

    private fun one(g: Game): String = listOf(
        g.position.encode(),
        if (g.human == Side.RED) "r" else "b",
        g.level.toString(),
        g.plies.toString(),
        g.captured.joinToString(","),
        g.lastMove?.let { "${it.from},${it.to},${it.piece},${it.captured}" } ?: "",
        g.result?.let { "${if (it.winner == Side.RED) "r" else "b"},${it.reason.name},${it.piece}" } ?: "",
        g.litLeft.toString(),
        g.elapsedMs.toString(),
    ).joinToString("|")

    private fun parseOne(s: String): Game? {
        val f = s.split("|")
        if (f.size != 9) return null
        val pos = Position.decode(f[0]) ?: return null
        val human = if (f[1] == "r") Side.RED else Side.BLUE
        val captured = if (f[4].isEmpty()) emptyList() else f[4].split(",").map { it.toInt() }
        val last = if (f[5].isEmpty()) null else f[5].split(",").map { it.toInt() }.let { Move(it[0], it[1], it[2], it[3]) }
        val result = if (f[6].isEmpty()) null else f[6].split(",").let { GameResult(if (it[0] == "r") Side.RED else Side.BLUE, io.embodit.junglechess.domain.model.WinReason.valueOf(it[1]), it[2].toInt()) }
        require(captured.all { it in 0 until Pieces.COUNT })
        require(last == null || (last.from in 0 until Sq.COUNT && last.to in 0 until Sq.COUNT))
        return Game(pos, human, f[2].toInt().coerceIn(1, 5), f[3].toInt(), captured, last, result, f[7].toInt(), f[8].toLong())
    }
}
