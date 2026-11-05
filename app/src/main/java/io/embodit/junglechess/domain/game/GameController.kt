package io.embodit.junglechess.domain.game

import io.embodit.junglechess.domain.engine.Engine
import io.embodit.junglechess.domain.model.Animal
import io.embodit.junglechess.domain.model.GameStatus
import io.embodit.junglechess.domain.model.Move
import io.embodit.junglechess.domain.model.Pieces
import io.embodit.junglechess.domain.model.Position
import io.embodit.junglechess.domain.model.Side
import io.embodit.junglechess.domain.model.Sq
import io.embodit.junglechess.domain.model.Terrain
import io.embodit.junglechess.domain.model.WinReason
import io.embodit.junglechess.domain.rules.Illegal
import io.embodit.junglechess.domain.rules.JungleRules
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.random.Random

/** Where the game and the settings are kept between launches. */
interface GameStore {
    fun loadGame(): String?
    fun saveGame(text: String?)
    fun loadSettings(): Settings
    fun saveSettings(settings: Settings)
}

/** Sound cues the flow asks for; the platform plays them. */
enum class Cue { SELECT, PLACE, CAPTURE, TOPPLE, JUMP, SPLASH, DRIP, TRAP, ILLEGAL, ALARM, WIN, LOSE }

/**
 * The single source of truth for both windows (board volume and control window) — rule layer
 * [JungleRules], state layer here, render layer in ui/. Every input (pinch / ray taps on the board, drags,
 * buttons in the control window, and the debug broadcast) goes through these methods.
 *
 * Move order (Cloud Air rule): a move is applied and saved first, then the board animates it; the board
 * reports [animationDone], and a watchdog finishes the step if it never does.
 */
class GameController(
    private val scope: CoroutineScope,
    private val compute: CoroutineDispatcher,
    private val store: GameStore,
    private val now: () -> Long,
    private val log: (String) -> Unit = {},
    private val cue: (Cue, Animal?) -> Unit = { _, _ -> },
    seed: Long = System.nanoTime(),
) {
    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()
    private val s get() = _state.value

    private val random = Random(seed)
    /** Searches running right now (each on its own Engine instance; tests assert a cancelled one stops). */
    val runningSearches = java.util.concurrent.atomic.AtomicInteger(0)
    private var history = ArrayList<Game>()
    private val redo = ArrayList<Game>()
    private var aiJob: Job? = null
    private var hintJob: Job? = null
    private var watchdog: Job? = null
    private var flashJob: Job? = null
    private var animToken = 0
    private var flashToken = 0
    private var turnStartedAt = 0L
    private var savedHistory: List<Game> = emptyList()
    private var alertSounded = false

    /** Minimum time the AI "thinks" so its move is readable (design package gameSpec.ai.minThinkMs). */
    var minThinkMs = 400L
    /** Watchdog margin over the planned animation length. */
    var watchdogMs = 1500L

    // ------------------------------------------------------------------ start / restore

    fun start() {
        val settings = store.loadSettings()
        val saved = store.loadGame()?.let { GameCodec.decode(it) }
        if (saved == null && store.loadGame() != null) log("SAVE unreadable, ignored")
        val savedGame = saved?.first?.takeIf { it.result == null }
        savedHistory = saved?.second ?: emptyList()
        val draftSide = if (savedGame?.human == Side.BLUE) SideChoice.BLUE else SideChoice.RED
        _state.value = UiState(phase = Phase.SETUP, settings = settings, savedGame = savedGame, draftSide = draftSide, draftLevel = savedGame?.level ?: 1)
        when {
            !settings.firstRunDone -> setTab(Tab.RULES)
            savedGame != null -> restore(savedGame, saved.second)
            else -> setTab(Tab.NEW)
        }
        log("GAME start firstRun=${!settings.firstRunDone} saved=${savedGame != null}")
    }

    private fun restore(game: Game, hist: List<Game>) {
        history = ArrayList(hist)
        redo.clear()
        turnStartedAt = now() // time away from the app is not playing time
        _state.update { it.copy(game = game, phase = Phase.IDLE, tab = Tab.PLAY, restored = true, line = Line(LineKey.RESTORED), savedGame = null) }
        log("GAME restored plies=${game.plies} toMove=${game.position.toMove}")
        afterTurn(fromRestore = true)
    }

    // ------------------------------------------------------------------ pages and settings

    fun setTab(tab: Tab) { _state.update { it.copy(tab = tab, confirm = null) }; log("TAB $tab state=${s.stateCode}") }

    /** Rules page [开始下棋]: first run is over; go to the new-game page. */
    fun rulesDone() {
        updateSettings { it.copy(firstRunDone = true) }
        setTab(if (s.game != null && s.game?.result == null) Tab.PLAY else Tab.NEW)
    }

    fun updateSettings(change: (Settings) -> Settings) {
        val next = change(s.settings)
        store.saveSettings(next)
        _state.update { st -> st.copy(settings = next, lit = litPieces(st.copy(settings = next))) }
        log("SETTINGS $next")
    }

    fun setDraft(side: SideChoice? = null, level: Int? = null) {
        _state.update { it.copy(draftSide = side ?: it.draftSide, draftLevel = (level ?: it.draftLevel).coerceIn(1, 5)) }
    }

    fun modelsLoaded(ok: Boolean) {
        _state.update { it.copy(modelsReady = true, modelsFailed = !ok) }
        log("MODELS ${if (ok) "ready" else "failed"}")
    }

    // ------------------------------------------------------------------ new game

    fun newGame(side: SideChoice = s.draftSide, level: Int = s.draftLevel) {
        cancelAi(); cancelHint(); watchdog?.cancel()
        val human = when (side) {
            SideChoice.RED -> Side.RED
            SideChoice.BLUE -> Side.BLUE
            SideChoice.RANDOM -> if (random.nextBoolean()) Side.RED else Side.BLUE
        }
        val settings = s.settings
        val firstGame = settings.gamesStarted == 0 && level <= 2
        val game = Game(Position.start(), human, level.coerceIn(1, 5), litLeft = if (firstGame || settings.litAlways) 3 else 0)
        updateSettings { it.copy(gamesStarted = it.gamesStarted + 1, firstRunDone = true) }
        history = ArrayList()
        redo.clear()
        _state.update {
            it.copy(
                game = game, phase = Phase.IDLE, tab = Tab.PLAY, selected = -1, targets = emptyList(), blockers = emptyList(),
                hint = null, line = Line(LineKey.PICK_ONE), denThreat = emptyList(), anim = null, restored = false, confirm = null,
                savedGame = null, draftSide = side, draftLevel = level.coerceIn(1, 5),
            )
        }
        turnStartedAt = now()
        save()
        log("GAME new human=$human level=$level lit=${game.litLeft}")
        afterTurn()
    }

    fun continueSaved() {
        val g = s.savedGame ?: return
        restore(g, savedHistory)
    }

    /** Result card [再来一局]: same side and level. */
    fun again() {
        val g = s.game ?: return newGame()
        newGame(if (g.human == Side.RED) SideChoice.RED else SideChoice.BLUE, g.level)
    }

    fun mainMenu() { setTab(Tab.NEW) }

    // ------------------------------------------------------------------ board input

    /** A pinch / trigger on a square (pieces resolve to their square). Same path for the debug `tap`. */
    fun tap(square: Int) {
        val st = s
        val game = st.game ?: return
        if (st.anim != null || st.phase == Phase.ANIMATING) return
        if (game.result != null || st.tab == Tab.NEW && st.phase == Phase.SETUP) return
        if (!st.humanToMove) return
        val pos = game.position
        val here = pos.at(square)
        val sel = st.selected
        if (sel >= 0) {
            val m = st.targets.firstOrNull { it.to == square }
            if (m != null) { play(m); return }
            if (here >= 0 && Pieces.side(here) == game.human && square != sel) { select(square); return }
            if (square == sel) { deselect(LineKey.CANCELLED); return }
            illegal(square, JungleRules.whyIllegal(pos, sel, square))
            return
        }
        when {
            here >= 0 && Pieces.side(here) == game.human -> {
                if (JungleRules.legalMovesFrom(pos, square).isEmpty()) {
                    flash(square, Line(LineKey.CANT_MOVE, Pieces.animal(here)))
                } else select(square)
            }
            here >= 0 -> flash(square, Line(LineKey.NOT_YOURS, Pieces.animal(here)))
            else -> {}
        }
    }

    /** Pinch on empty space / outside the board: cancel the selection. */
    fun tapNothing() { if (s.selected >= 0) deselect(LineKey.CANCELLED) }

    /** Drag start on a piece: select it (it rises and follows the pointer in the scene). */
    fun dragStart(square: Int): Boolean {
        val game = s.game ?: return false
        if (!s.humanToMove || s.anim != null) return false
        val id = game.position.at(square)
        if (id < 0 || Pieces.side(id) != game.human) return false
        if (s.selected != square) {
            if (JungleRules.legalMovesFrom(game.position, square).isEmpty()) { flash(square, Line(LineKey.CANT_MOVE, Pieces.animal(id))); return false }
            select(square)
        }
        return true
    }

    /**
     * Drag released over [square] (the scene projects the pointer to the nearest legal square within reach,
     * D27 forgiving); -1 = off the board or nowhere legal: the piece springs back and stays selected.
     */
    fun dragEnd(square: Int) {
        val m = s.targets.firstOrNull { it.to == square }
        if (m != null) play(m) else log("DRAG released square=${if (square >= 0) Sq.name(square) else "-"} -> back")
    }

    private fun select(square: Int) {
        val game = s.game ?: return
        val pos = game.position
        val moves = JungleRules.legalMovesFrom(pos, square)
        val id = pos.at(square)
        val animal = Pieces.animal(id)
        val blockers = JungleRules.jumpBlockers(pos, square)
        val line = when {
            moves.any { it.isJump } -> Line(LineKey.CAN_JUMP, animal)
            blockers.isNotEmpty() -> Line(LineKey.RAT_BLOCKS, animal)
            animal == Animal.RAT && Terrain.isRiver(square) -> Line(LineKey.RAT_SAFE_IN_RIVER, animal)
            animal == Animal.RAT && moves.any { Terrain.isRiver(it.to) } -> Line(LineKey.RAT_SWIM, animal)
            moves.any { it.captured >= 0 } -> Line(LineKey.CAN_CAPTURE, animal, Pieces.animal(moves.first { it.captured >= 0 }.captured))
            else -> Line(LineKey.ANY_LIT, animal)
        }
        _state.update { it.copy(selected = square, targets = moves, blockers = blockers, line = line, phase = Phase.SELECTED, restored = false) }
        cue(Cue.SELECT, animal)
        log("SELECT ${Sq.name(square)} ${animal.en} targets=${moves.joinToString(",") { Sq.name(it.to) }} blockers=${blockers.joinToString(",") { Sq.name(it) }}")
    }

    private fun deselect(key: LineKey) {
        _state.update { it.copy(selected = -1, targets = emptyList(), blockers = emptyList(), line = Line(key), phase = Phase.IDLE) }
    }

    private fun illegal(square: Int, why: Illegal) {
        val game = s.game ?: return
        val pos = game.position
        val sel = s.selected
        val a = if (sel >= 0) Pieces.animal(pos.at(sel)) else null
        val other = pos.at(square).takeIf { it >= 0 }?.let { Pieces.animal(it) }
        flash(square, Line(LineKey.ILLEGAL, a, other, why))
        log("ILLEGAL ${if (sel >= 0) Sq.name(sel) else "-"}->${Sq.name(square)} $why")
    }

    private fun flash(square: Int, line: Line) {
        val token = ++flashToken
        _state.update { it.copy(flash = Flash(square, token), line = line) }
        cue(Cue.ILLEGAL, null)
        flashJob?.cancel()
        flashJob = scope.launch {
            delay(FLASH_MS)
            _state.update { if (it.flash?.token == token) it.copy(flash = null) else it }
        }
    }

    // ------------------------------------------------------------------ moves

    /** Commits [move] (human or AI), saves, then hands it to the board to animate. */
    fun play(move: Move) {
        val st = s
        val game = st.game ?: return
        if (!JungleRules.isLegal(game.position, move)) { log("MOVE rejected ${move.notation()}"); return }
        cancelHint()
        val before = game.position
        val after = JungleRules.apply(before, move)
        val status = JungleRules.status(after)
        // the same position (same side to move) a third time is a draw (critic R10: no endless shuffling)
        val seen = history.count { it.position == after } + (if (game.position == after) 1 else 0)
        val result = (status as? GameStatus.Won)?.let { GameResult(it.winner, it.reason, move.piece) }
            ?: if (seen >= 2) GameResult(null, WinReason.REPETITION) else null
        val byHuman = Pieces.side(move.piece) == game.human
        history.add(game)
        if (history.size > GameCodec.MAX_HISTORY) history.removeAt(0)
        redo.clear()
        val next = game.copy(
            position = after,
            plies = game.plies + 1,
            captured = if (move.captured >= 0) game.captured + move.captured else game.captured,
            lastMove = move,
            result = result,
            litLeft = if (byHuman && game.litLeft > 0) game.litLeft - 1 else game.litLeft,
            elapsedMs = game.elapsedMs + (now() - turnStartedAt).coerceAtLeast(0),
        )
        turnStartedAt = now()
        val token = ++animToken
        val anim = Anim(token, move, before, after, st.settings.reducedMotion)
        val line = moveLine(move, byHuman)
        _state.update {
            it.copy(
                game = next, phase = Phase.ANIMATING, anim = anim, selected = -1, targets = emptyList(), blockers = emptyList(),
                hint = null, line = line, denThreat = emptyList(), restored = false, flash = null, lit = emptyList(),
            )
        }
        save()
        playCues(move)
        log("MOVE ${if (byHuman) "human" else "ai"} ${move.notation()} jump=${move.isJump} result=$result plies=${next.plies}")
        val plan = MotionPlan.of(move, anim.reduced)
        watchdog?.cancel()
        watchdog = scope.launch {
            delay(plan.totalMs + watchdogMs)
            if (s.anim?.token == token) { log("MOVE watchdog token=$token"); animationDone(token) }
        }
    }

    private fun playCues(move: Move) {
        val a = Pieces.animal(move.piece)
        val c = if (move.captured >= 0) Pieces.animal(move.captured) else null
        when {
            move.isJump -> cue(Cue.JUMP, a)
            a == Animal.RAT && c == Animal.ELEPHANT -> cue(Cue.TOPPLE, c)
            Terrain.isRiver(move.to) && !Terrain.isRiver(move.from) -> cue(Cue.SPLASH, a)
            Terrain.isRiver(move.from) && !Terrain.isRiver(move.to) -> cue(Cue.DRIP, a)
            else -> cue(Cue.PLACE, a)
        }
        if (c != null && !(a == Animal.RAT && c == Animal.ELEPHANT)) cue(Cue.CAPTURE, c)
        val owner = Terrain.trapOwner(move.to)
        if (owner != null && owner != Pieces.side(move.piece)) cue(Cue.TRAP, a)
    }

    private fun moveLine(move: Move, byHuman: Boolean): Line {
        val a = Pieces.animal(move.piece)
        val c = if (move.captured >= 0) Pieces.animal(move.captured) else null
        return when {
            !byHuman && c != null -> Line(LineKey.THEY_CAPTURED, a, c)
            move.isJump -> Line(LineKey.JUMPED, a, c)
            a == Animal.RAT && c == Animal.ELEPHANT -> Line(LineKey.TOPPLED, a, c)
            c != null -> Line(LineKey.CAPTURED, a, c)
            else -> Line(LineKey.MOVED, a)
        }
    }

    /** The board finished animating [token] (or the watchdog did). */
    fun animationDone(token: Int) {
        if (s.anim?.token != token) return
        watchdog?.cancel()
        _state.update { it.copy(anim = null) }
        log("MOVE done token=$token")
        afterTurn()
    }

    /** Decides what comes after a settled position: result, AI turn or the human's turn. */
    private fun afterTurn(fromRestore: Boolean = false) {
        val game = s.game ?: return
        val result = game.result
        if (result != null) {
            _state.update { it.copy(phase = Phase.RESULT, line = Line(LineKey.RESULT, result.piece.takeIf { p -> p >= 0 }?.let { p -> Pieces.animal(p) }), lit = emptyList()) }
            cue(when (result.winner) { game.human -> Cue.WIN; null -> Cue.PLACE; else -> Cue.LOSE }, null)
            log("RESULT winner=${result.winner} reason=${result.reason} human=${game.human}")
            return
        }
        if (game.position.toMove == game.ai) {
            think()
            return
        }
        val threats = JungleRules.denThreats(game.position, game.human)
        val line = when {
            threats.isNotEmpty() -> Line(LineKey.DEN_ALERT, Pieces.animal(game.position.at(threats.first())))
            fromRestore -> Line(LineKey.RESTORED)
            s.line.key in setOf(LineKey.THEY_CAPTURED) -> s.line
            else -> Line(LineKey.YOUR_MOVE)
        }
        val wasThreatened = alertSounded
        _state.update { it.copy(phase = Phase.IDLE, denThreat = threats, line = line, restored = fromRestore) }
        _state.update { it.copy(lit = litPieces(it)) }
        alertSounded = threats.isNotEmpty()
        if (threats.isNotEmpty()) {
            // the drum only when the threat appears, not every turn it lasts (critic S4)
            if (!wasThreatened) cue(Cue.ALARM, null)
            log("ALERT den threatened by ${threats.joinToString(",") { Sq.name(it) }}")
        }
        if (s.hintOn) requestHint()
    }

    private fun litPieces(st: UiState): List<Int> {
        val game = st.game ?: return emptyList()
        if (!st.humanToMove || st.phase != Phase.IDLE) return emptyList()
        if (game.litLeft <= 0 && !st.settings.litAlways) return emptyList()
        return game.position.pieceIds(game.human).map { game.position.squareOf(it) }.filter { JungleRules.legalMovesFrom(game.position, it).isNotEmpty() }
    }

    private fun think() {
        val game = s.game ?: return
        _state.update { it.copy(phase = Phase.THINKING, line = if (it.line.key == LineKey.RESTORED) it.line else Line(LineKey.THINKING)) }
        cancelAi()
        val started = now()
        val pos = game.position
        val level = game.level
        log("AI think level=$level")
        // critic R1: a fresh Engine per search (no shared mutable state between searches) and a cancel check that
        // reads this coroutine's own state, so undo / new game stops the old search within a few ms.
        val engine = Engine(random.nextLong())
        val reps = repeats(game)
        aiJob = scope.launch {
            val move = withContext(compute) {
                val ctx = coroutineContext
                runningSearches.incrementAndGet()
                try { engine.choose(pos, level, cancel = { !ctx.isActive }, repeated = reps) } finally { runningSearches.decrementAndGet() }
            }
            val waited = now() - started
            if (waited < minThinkMs) delay(minThinkMs - waited)
            log("AI chose ${move?.notation()} in ${now() - started} ms depth=${engine.lastDepth} nodes=${engine.lastNodes}")
            if (s.game?.position != pos || s.phase != Phase.THINKING) return@launch
            if (move == null) return@launch
            play(move)
        }
    }

    /** How often each position occurred in this game (the engine scores a repeat as a draw). */
    private fun repeats(game: Game): Map<Position, Int> = (history.map { it.position } + game.position).groupingBy { it }.eachCount()

    private fun cancelAi() { aiJob?.cancel(); aiJob = null }

    // ------------------------------------------------------------------ undo / redo / hint / resign

    /** One whole round back (with the AI's reply); during the AI's thinking only the human move (T6). */
    fun undo() {
        val st = s
        val game = st.game ?: return
        if (st.anim != null) return
        if (history.isEmpty()) { _state.update { it.copy(line = Line(LineKey.NOTHING_TO_UNDO)) }; return }
        cancelAi(); cancelHint()
        var target = history.removeAt(history.lastIndex)
        redo.add(game)
        // step back until it is the human's turn again (AI reply + human move)
        while (target.position.toMove != target.human && history.isNotEmpty()) {
            redo.add(target)
            target = history.removeAt(history.lastIndex)
        }
        _state.update {
            it.copy(game = target.copy(result = null), phase = Phase.IDLE, selected = -1, targets = emptyList(), blockers = emptyList(), hint = null,
                line = Line(LineKey.UNDONE), denThreat = emptyList(), anim = null, confirm = null, tab = Tab.PLAY)
        }
        turnStartedAt = now()
        save()
        log("UNDO to plies=${target.plies}")
        afterTurn()
        if (s.line.key == LineKey.YOUR_MOVE) _state.update { it.copy(line = Line(LineKey.UNDONE)) }
    }

    fun redo() {
        val st = s
        val game = st.game ?: return
        if (st.anim != null || redo.isEmpty() || st.phase == Phase.THINKING) return
        cancelHint()
        var target = redo.removeAt(redo.lastIndex)
        history.add(game)
        while (target.position.toMove != target.human && target.result == null && redo.isNotEmpty()) {
            history.add(target)
            target = redo.removeAt(redo.lastIndex)
        }
        _state.update { it.copy(game = target, phase = Phase.IDLE, selected = -1, targets = emptyList(), blockers = emptyList(), hint = null, line = Line(LineKey.REDONE), anim = null) }
        save()
        log("REDO to plies=${target.plies}")
        afterTurn()
    }

    val canUndo: Boolean get() = history.isNotEmpty()
    val canRedo: Boolean get() = redo.isNotEmpty()

    fun setHint(on: Boolean) {
        _state.update { it.copy(hintOn = on, hint = null, line = Line(if (on) LineKey.HINT_ON else LineKey.HINT_OFF)) }
        if (on) requestHint() else cancelHint()
        log("HINT $on")
    }

    private fun requestHint() {
        val game = s.game ?: return
        if (!s.humanToMove) return
        cancelHint()
        val pos = game.position
        val hintEngine = Engine(random.nextLong())
        val reps = repeats(game)
        hintJob = scope.launch {
            val m = withContext(compute) {
                val ctx = coroutineContext
                runningSearches.incrementAndGet()
                try { hintEngine.hint(pos, cancel = { !ctx.isActive }, repeated = reps) } finally { runningSearches.decrementAndGet() }
            }
            if (s.game?.position == pos && s.hintOn) { _state.update { it.copy(hint = m) }; log("HINT ${m?.notation()}") }
        }
    }

    private fun cancelHint() { hintJob?.cancel(); hintJob = null }

    fun askConfirm(c: Confirm?) { _state.update { it.copy(confirm = c) }; log("CONFIRM $c state=${s.stateCode}") }

    fun resign() {
        val game = s.game ?: return
        if (game.result != null) return
        cancelAi(); cancelHint()
        val g = game.copy(result = GameResult(game.ai, WinReason.RESIGN))
        _state.update { it.copy(game = g, confirm = null, anim = null, selected = -1, targets = emptyList(), tab = Tab.PLAY) }
        save()
        afterTurn()
    }

    fun restart() { askConfirm(null); again() }

    // ------------------------------------------------------------------ debug helpers (same paths)

    /** Debug `setup <position>`: a custom position, human to move, history cleared. */
    fun setup(pos: Position, human: Side = Side.RED, level: Int = s.game?.level ?: 1) {
        cancelAi(); cancelHint()
        history = ArrayList(); redo.clear()
        turnStartedAt = now()
        _state.update { it.copy(game = Game(pos, human, level), phase = Phase.IDLE, tab = Tab.PLAY, anim = null, selected = -1, targets = emptyList(), hint = null, restored = false, confirm = null) }
        save()
        afterTurn()
    }

    fun saveNow() = save()

    private fun save() {
        val g = s.game ?: return
        runCatching { store.saveGame(GameCodec.encode(g, history)) }.onFailure { log("SAVE failed $it") }
    }

    fun historySize(): Int = history.size

    companion object {
        var FLASH_MS = 300L // debug `freeze on` stretches it so the red frame can be photographed (QA k3)
    }
}
