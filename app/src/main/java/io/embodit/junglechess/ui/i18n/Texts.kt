package io.embodit.junglechess.ui.i18n

import io.embodit.junglechess.domain.game.GameResult
import io.embodit.junglechess.domain.game.Language
import io.embodit.junglechess.domain.game.Line
import io.embodit.junglechess.domain.game.LineKey
import io.embodit.junglechess.domain.model.Animal
import io.embodit.junglechess.domain.model.Pieces
import io.embodit.junglechess.domain.model.Side
import io.embodit.junglechess.domain.model.WinReason
import io.embodit.junglechess.domain.rules.Illegal
import java.util.Locale

/**
 * All copy in Chinese and English (design package content.tone, sample §10: short, spoken, like a friend
 * next to you). Pure Kotlin so the overflow / length checks can run on the JVM.
 */
class Texts(val zh: Boolean) {
    fun t(zh: String, en: String): String = if (this.zh) zh else en

    fun animal(a: Animal?): String = when {
        a == null -> ""
        zh -> a.zh
        else -> a.en
    }

    private fun capA(a: Animal?) = animal(a).replaceFirstChar { it.uppercase() }

    fun animalLabel(a: Animal): String = if (zh) "${a.zh}${a.rank}" else "${a.letter}${a.rank}"

    // ---- status line ----
    fun line(l: Line, result: GameResult? = null, human: Side = Side.RED): String = when (l.key) {
        LineKey.PICK_ONE -> t("轮到你。先点一只自己的动物。", "Your move. Pick one of your animals.")
        LineKey.YOUR_MOVE -> t("轮到你。", "Your move.")
        LineKey.CAN_JUMP -> t("${animal(l.animal)}可以跳河了。", "The ${animal(l.animal)} can jump the river.")
        LineKey.RAT_SWIM -> t("老鼠在河边，它想不想下水？", "The rat's by the river. Fancy a swim?")
        LineKey.RAT_SAFE_IN_RIVER -> t("老鼠在河里，谁也吃不了它。", "The rat's in the river. Nobody can reach it.")
        LineKey.CAN_CAPTURE -> t("能吃对方的${animal(l.other)}。", "You can take their ${animal(l.other)}.")
        LineKey.ANY_LIT -> t("亮点的格子都能走。", "Any lit square works.")
        LineKey.RAT_BLOCKS -> t("河里有老鼠，跳不过去。", "There's a rat in the river. No jumping.")
        LineKey.ILLEGAL -> illegal(l.illegal, l.animal, l.other)
        LineKey.NOT_YOURS -> t("那是对方的。先点一只自己的。", "That's theirs. Pick one of yours first.")
        LineKey.CANT_MOVE -> t("这只现在动不了。", "That one can't move right now.")
        LineKey.CANCELLED -> t("取消了。", "Cancelled.")
        LineKey.THINKING -> t("对方在想…", "Thinking…")
        LineKey.JUMPED -> t("跳过去了。", "Over the river!")
        LineKey.CAPTURED -> t("吃了对方的${animal(l.other)}。", "Got their ${animal(l.other)}.")
        LineKey.TOPPLED -> t("老鼠把象放倒了。", "The rat toppled the elephant.")
        LineKey.MOVED -> t("走好了。", "Done.")
        LineKey.THEY_CAPTURED -> t("对方吃了你的${animal(l.other)}。轮到你。", "They took your ${animal(l.other)}. Your move.")
        LineKey.DEN_ALERT -> t("它要进你家了，挡住它。", "It's heading for your den. Block it.")
        LineKey.RESTORED -> t("接着上次下。", "Picking up where you left off.")
        LineKey.UNDONE -> t("退了一回合。", "Took back a turn.")
        LineKey.REDONE -> t("又走回来了。", "Back to where you were.")
        LineKey.NOTHING_TO_UNDO -> t("还没走呢，没得退。", "Nothing to take back yet.")
        LineKey.HINT_ON -> t("提示开了：黄环是推荐的子，黄框是去哪。", "Hint on: yellow ring = which one, yellow frame = where.")
        LineKey.HINT_OFF -> t("提示关了。", "Hint off.")
        LineKey.NEW_GAME -> t("新开了一盘。", "New game.")
        LineKey.RESULT -> result?.let { resultTitle(it, human) } ?: ""
    }

    fun illegal(why: Illegal?, a: Animal?, other: Animal?): String = when (why) {
        Illegal.TOO_FAR, null -> t("一次只能走一格，不能斜着走。", "One square at a time, no diagonals.")
        Illegal.OWN_DEN -> t("这是你自己的家，进不去。", "That's your own den. Can't go in.")
        Illegal.ONLY_RAT_SWIMS -> t("只有老鼠能下水。", "Only the rat swims.")
        Illegal.RAT_BLOCKS_JUMP -> t("河里有老鼠，跳不过去。", "There's a rat in the river. No jumping.")
        Illegal.OWN_PIECE -> t("那是自己人。", "That's one of yours.")
        Illegal.ELEPHANT_RAT -> t("象吃不了鼠。换一只，或者先躲开。", "Elephants can't eat rats. Pick another one, or back off.")
        Illegal.WATER -> t("它在水里，够不着。", "It's in the water. Can't reach.")
        Illegal.OUTRANKED -> t("${animal(other)}比你的${animal(a)}大，吃不了。", "The ${animal(other)} outranks your ${animal(a)}.")
    }

    fun resultTitle(r: GameResult, human: Side): String {
        val win = r.winner == human
        val a = if (r.piece >= 0) Pieces.animal(r.piece) else null
        return when (r.reason) {
            WinReason.DEN -> if (win) t("${animal(a)}进了家。你赢了，再来？", "The ${animal(a)} walked into their den. You win. Again?")
            else t("对方的${animal(a)}进了你家。再来？", "Their ${animal(a)} got into your den. Again?")
            WinReason.ALL_CAPTURED -> if (win) t("对方没子了。你赢了，再来？", "They're out of animals. You win. Again?")
            else t("你的动物都被吃光了。再来？", "All your animals are gone. Again?")
            WinReason.NO_MOVES -> if (win) t("对方没棋走了。你赢了，再来？", "They're out of moves. You win. Again?")
            else t("你没棋走了。再来？", "You're out of moves. Again?")
            WinReason.RESIGN -> t("你认输了。再来一局？", "You resigned. Another one?")
        }
    }

    fun resultRule(r: GameResult): String = when (r.reason) {
        WinReason.DEN -> t("走进对方的兽穴就赢，按常见中文规则。", "Walk into their den and you win, by the common Chinese rules.")
        WinReason.ALL_CAPTURED -> t("吃光对方就赢，按常见中文规则。", "Take every animal and you win, by the common Chinese rules.")
        WinReason.NO_MOVES -> t("无子可动判负，按常见中文规则。", "No legal moves means a loss, by the common Chinese rules.")
        WinReason.RESIGN -> t("认输算输，棋局留着。", "A resignation counts as a loss.")
    }

    // ---- turn header ----
    fun turnHeader(thinking: Boolean, humanToMove: Boolean, over: Boolean): String = when {
        over -> t("这盘下完了", "Game over")
        thinking -> t("对方在想…", "Thinking…")
        humanToMove -> t("轮到你", "Your move")
        else -> t("等一下", "Hold on")
    }

    fun seatCard(thinking: Boolean, humanSide: Side, toMove: Side?, over: Boolean): String = when {
        over -> t("结束 · Game over", "Game over")
        thinking -> t("对方在想 · Thinking", "Thinking…")
        toMove == null -> t("摆棋中 · Setting up", "Setting up…")
        toMove == Side.RED -> t("轮到红 · Red to move", "Red to move")
        else -> t("轮到蓝 · Blue to move", "Blue to move")
    }

    fun moveCounter(plies: Int, level: Int): String = t("第 ${plies / 2 + 1} 手 · ${levelName(level)}档", "Move ${plies / 2 + 1} · ${levelName(level)}")

    fun counts(red: Int, blue: Int): String = t("红吃了 $red · 蓝吃了 $blue", "Red took $red · Blue took $blue")

    fun levelName(n: Int): String = when (n) {
        1 -> t("入门", "Beginner")
        2 -> t("初级", "Easy")
        3 -> t("中级", "Normal")
        4 -> t("高级", "Hard")
        else -> t("大师", "Master")
    }

    fun levelBlurb(n: Int): String = when (n) {
        1 -> t("入门：它会乱走，你能赢。", "Beginner: it wanders, you'll win.")
        2 -> t("初级：会吃子，偶尔走神。", "Easy: takes pieces, dozes off.")
        3 -> t("中级：不送子。", "Normal: no freebies.")
        4 -> t("高级：会守家。", "Hard: guards the den.")
        else -> t("大师：想 6 步。", "Master: thinks 6 ahead.")
    }

    fun elapsed(ms: Long): String {
        val s = ms / 1000
        return t("用时 ${s / 60} 分 ${s % 60} 秒", "${s / 60} min ${s % 60} s")
    }

    companion object {
        fun of(language: Language): Texts = Texts(
            when (language) {
                Language.ZH -> true
                Language.EN -> false
                Language.SYSTEM -> Locale.getDefault().language == "zh"
            },
        )

        /** Every dynamic sentence that can appear, for the length / overflow checks (D13). */
        fun allLines(t: Texts): List<String> {
            val out = ArrayList<String>()
            for (k in LineKey.entries) for (a in Animal.entries) for (o in listOf(Animal.ELEPHANT, Animal.LEOPARD)) {
                if (k == LineKey.ILLEGAL) Illegal.entries.forEach { out += t.illegal(it, a, o) } else out += t.line(Line(k, a, o))
            }
            for (w in Side.entries) for (r in WinReason.entries) for (p in listOf(-1, 0, 6, 15)) {
                out += t.resultTitle(GameResult(w, r, p), Side.RED); out += t.resultRule(GameResult(w, r, p))
            }
            return out.distinct()
        }
    }
}
