package io.embodit.junglechess.ui.control

import android.app.ActivityManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pico.spatial.ui.design.Button
import com.pico.spatial.ui.design.ButtonDefaults
import com.pico.spatial.ui.design.Icon
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.design.SegmentSlider
import com.pico.spatial.ui.design.Switch
import com.pico.spatial.ui.design.Text
import com.pico.spatial.ui.design.windows.TabBar
import com.pico.spatial.ui.platform.ViewPoint
import io.embodit.junglechess.R
import io.embodit.junglechess.domain.game.Confirm
import io.embodit.junglechess.domain.game.GameController
import io.embodit.junglechess.domain.game.Language
import io.embodit.junglechess.domain.game.Phase
import io.embodit.junglechess.domain.game.SideChoice
import io.embodit.junglechess.domain.game.Tab
import io.embodit.junglechess.domain.game.UiState
import io.embodit.junglechess.domain.model.Animal
import io.embodit.junglechess.domain.model.Side
import io.embodit.junglechess.platform.Graph
import io.embodit.junglechess.ui.i18n.Texts
import io.embodit.junglechess.ui.theme.JungleColors
import io.embodit.junglechess.ui.theme.LocalTexts
import io.embodit.junglechess.ui.window.WindowCtl
import kotlin.system.exitProcess

private const val TAG = "JungleChess"

/** D13: every fixed-line text logs OVERFLOW when it does not fit (checked in both languages). */
private fun overflowLog(where: String): (TextLayoutResult) -> Unit = { r -> if (r.hasVisualOverflow) Log.w(TAG, "OVERFLOW $where") }

/**
 * The control window (Planar, 560 x 440 dp, right of the board): four pages switched by the system TabBar
 * (D8) — play / new game / rules / settings. Confirmations open inside the same page (S13), the result card
 * sits on the play page (S09). Dragging and closing are the system window controls (D21).
 */
@Composable
fun ControlScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val graph = remember(context) { Graph.from(context) }
    val c = graph.controller
    val s by c.state.collectAsStateWithLifecycle()
    val t = LocalTexts.current

    DisposableEffect(Unit) {
        WindowCtl.onControlCreated()
        onDispose { WindowCtl.onControlDestroyed() }
    }

    Box(modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().padding(start = 32.dp, end = 32.dp, top = 24.dp, bottom = 36.dp)) {
            when (s.tab) {
                Tab.PLAY -> PlayPage(s, c, t)
                Tab.NEW -> NewPage(s, c, t)
                Tab.RULES -> RulesPage(s, c, t)
                Tab.SETTINGS -> SettingsPage(s, c, t, onExit = { exitApp(context, graph) })
            }
        }
    }

    TabBar(followViewpoints = setOf(ViewPoint.Front, ViewPoint.Left)) {
        listOf(
            Triple(Tab.PLAY, R.drawable.ic_pets, t.t("对局", "Game")),
            Triple(Tab.NEW, R.drawable.ic_add_circle, t.t("新局", "New")),
            Triple(Tab.RULES, R.drawable.ic_book, t.t("规则", "Rules")),
            Triple(Tab.SETTINGS, R.drawable.ic_settings, t.t("设置", "Settings")),
        ).forEach { (tab, icon, label) ->
            item(
                selected = s.tab == tab,
                onClick = { c.setTab(tab) },
                mainContent = { Icon(painter = painterResource(icon), contentDescription = label) },
                supportContent = { Text(label) },
            )
        }
    }
}

/** D26 exit: release, close the windows, remove every task, end the process. */
private fun exitApp(context: Context, graph: Graph) {
    Log.i(TAG, "EXIT release + close windows + finishAndRemoveTask + exitProcess")
    runCatching { graph.controller.saveNow() }
    runCatching { graph.sounds.release() }
    WindowCtl.closeAll()
    runCatching {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        am.appTasks.forEach { runCatching { it.finishAndRemoveTask() } }
    }
    Handler(Looper.getMainLooper()).postDelayed({ exitProcess(0) }, 400)
}

// ---------------------------------------------------------------------- widgets

@Composable
private fun Title(text: String) {
    Text(text, style = PicoTheme.typography.titleLarge, color = PicoTheme.colorScheme.labelPrimary, maxLines = 2, overflow = TextOverflow.Ellipsis, onTextLayout = overflowLog("title/$text"))
}

@Composable
private fun Body(text: String, modifier: Modifier = Modifier, color: Color = PicoTheme.colorScheme.labelPrimary) {
    Text(text, style = PicoTheme.typography.bodyLarge, color = color, modifier = modifier)
}

@Composable
private fun Small(text: String) {
    Text(text, style = PicoTheme.typography.bodySmall, color = PicoTheme.colorScheme.labelSecondary)
}

/** Button with an icon and text, >= 48 dp (B19). [primary] uses the interaction colour. */
@Composable
private fun ActionButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    @DrawableRes icon: Int? = null,
    primary: Boolean = false,
    danger: Boolean = false,
    enabled: Boolean = true,
    tag: String = text,
) {
    Button(
        onClick = onClick,
        modifier = modifier.heightIn(min = 48.dp),
        enabled = enabled,
        size = ButtonDefaults.Regular.copy(height = 52.dp, minWidth = 100.dp),
        shape = RoundedCornerShape(26.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = when { danger -> PicoTheme.colorScheme.error; primary -> PicoTheme.colorScheme.interaction; else -> PicoTheme.colorScheme.fillSecondary },
            contentColor = if (primary || danger) PicoTheme.colorScheme.labelPrimaryLight else PicoTheme.colorScheme.labelPrimary,
        ),
    ) {
        if (icon != null) {
            // design-style: inherited-content-color Button
            Icon(painter = painterResource(icon), contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
        }
        // design-style: inherited-content-color Button
        Text(text, style = PicoTheme.typography.labelLarge, maxLines = 1, softWrap = false, onTextLayout = overflowLog("button/$tag"))
    }
}

@Composable
private fun Choice(text: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Button(
        onClick = onClick,
        modifier = modifier.heightIn(min = 48.dp),
        size = ButtonDefaults.Regular.copy(height = 48.dp, minWidth = 72.dp),
        shape = RoundedCornerShape(24.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = if (selected) PicoTheme.colorScheme.interaction else PicoTheme.colorScheme.fillSecondary,
            contentColor = if (selected) PicoTheme.colorScheme.labelPrimaryLight else PicoTheme.colorScheme.labelPrimary,
        ),
    ) {
        // design-style: inherited-content-color Button
        Text(text, style = PicoTheme.typography.labelLarge, maxLines = 1, softWrap = false, onTextLayout = overflowLog("choice/$text"))
    }
}

@Composable
private fun SettingRow(label: String, content: @Composable RowScope.() -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = PicoTheme.typography.bodyLarge, color = PicoTheme.colorScheme.labelPrimary, modifier = Modifier.weight(1f), maxLines = 1, onTextLayout = overflowLog("setting/$label"))
        content()
    }
}

/** Two equal buttons in a row, inside the page (S13). */
@Composable
private fun ConfirmCard(question: String, detail: String, no: String, yes: String, onNo: () -> Unit, onYes: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(PicoTheme.colorScheme.fillTertiary).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(question, style = PicoTheme.typography.titleMedium, color = JungleColors.Ink)
        Text(detail, style = PicoTheme.typography.bodyMedium, color = JungleColors.Ink)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ActionButton(no, onNo, Modifier.weight(1f), primary = true, tag = "confirm-no")
            ActionButton(yes, onYes, Modifier.weight(1f), danger = true, tag = "confirm-yes")
        }
    }
}

@Composable
private fun SideDot(side: Side) {
    Box(Modifier.size(16.dp).clip(CircleShape).background(if (side == Side.RED) JungleColors.Red else JungleColors.Blue))
}

// ---------------------------------------------------------------------- pages

@Composable
private fun ColumnScope.PlayPage(s: UiState, c: GameController, t: Texts) {
    val g = s.game
    if (s.phase == Phase.LOADING) { Title(t.t("摆棋中…", "Setting up…")); return }
    if (g == null) {
        Title(t.t("还没开局", "No game yet"))
        Spacer(Modifier.height(12.dp))
        Body(t.t("去「新局」选好对手，按开始。", "Open \"New\", pick an opponent and start."))
        Spacer(Modifier.height(16.dp))
        ActionButton(t.t("去开一局", "Start a game"), { c.setTab(Tab.NEW) }, icon = R.drawable.ic_add_circle, primary = true)
        return
    }
    val thinking = s.phase == Phase.THINKING
    val over = g.result != null
    Row(verticalAlignment = Alignment.CenterVertically) {
        SideDot(if (thinking) g.ai else g.human)
        Spacer(Modifier.width(10.dp))
        Text(t.turnHeader(thinking, s.humanToMove, over), style = PicoTheme.typography.titleLarge, color = PicoTheme.colorScheme.labelPrimary, maxLines = 1, onTextLayout = overflowLog("play/turn"))
        Spacer(Modifier.weight(1f))
        Small(t.moveCounter(g.plies, g.level))
    }
    Spacer(Modifier.height(8.dp))
    if (over) {
        ResultCard(s, c, t)
        return
    }
    Body(t.line(s.line, g.result, g.human), Modifier.heightIn(min = 52.dp), color = if (s.denThreat.isNotEmpty() || s.flash != null) PicoTheme.colorScheme.error else PicoTheme.colorScheme.labelPrimary)
    if (s.modelsFailed) Small(t.t("动物模型没加载，先用底座下。", "The animal models didn't load; playing with the bases."))
    Spacer(Modifier.weight(1f))
    when (s.confirm) {
        Confirm.RESIGN -> {
            ConfirmCard(t.t("真的认输？", "Resign?"), t.t("这盘算输，棋局留着。", "This one counts as a loss."), t.t("不", "No"), t.t("认输", "Resign"), { c.askConfirm(null) }, { c.resign() })
            return
        }
        Confirm.RESTART -> {
            ConfirmCard(t.t("重新开一盘？", "Start over?"), t.t("这盘就不要了。", "This game will be dropped."), t.t("不", "No"), t.t("重开", "Restart"), { c.askConfirm(null) }, { c.restart() })
            return
        }
        else -> {}
    }
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        ActionButton(t.t("悔一步", "Undo"), { c.undo() }, Modifier.weight(1f), icon = R.drawable.ic_undo, enabled = s.anim == null)
        ActionButton(t.t("重来一步", "Redo"), { c.redo() }, Modifier.weight(1f), icon = R.drawable.ic_redo, enabled = s.anim == null && !thinking)
        ActionButton(if (s.hintOn) t.t("提示：开", "Hint: on") else t.t("提示：关", "Hint: off"), { c.setHint(!s.hintOn) }, Modifier.weight(1f), icon = R.drawable.ic_lightbulb, tag = "hint")
    }
    Spacer(Modifier.height(12.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        val muted = !s.settings.sound
        ActionButton(if (muted) t.t("取消静音", "Unmute") else t.t("静音", "Mute"), { c.updateSettings { it.copy(sound = muted) } }, Modifier.weight(1f), icon = if (muted) R.drawable.ic_volume_off else R.drawable.ic_volume_up, tag = "mute")
        ActionButton(t.t("认输", "Resign"), { c.askConfirm(Confirm.RESIGN) }, Modifier.weight(1f), icon = R.drawable.ic_flag)
    }
    Spacer(Modifier.height(12.dp))
    Small(t.counts(g.capturedBy(Side.RED).size, g.capturedBy(Side.BLUE).size))
}

@Composable
private fun ColumnScope.ResultCard(s: UiState, c: GameController, t: Texts) {
    val g = s.game ?: return
    val r = g.result ?: return
    val win = r.winner == g.human
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(if (win) PicoTheme.colorScheme.passable else PicoTheme.colorScheme.fillTertiary).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(t.resultTitle(r, g.human), style = PicoTheme.typography.titleMedium, color = if (win) PicoTheme.colorScheme.labelPrimaryLight else JungleColors.Ink)
        Text("${t.moveCounter(g.plies, g.level)} · ${t.elapsed(g.elapsedMs)}", style = PicoTheme.typography.bodySmall, color = if (win) PicoTheme.colorScheme.labelPrimaryLight else JungleColors.Ink)
        Text(t.resultRule(r), style = PicoTheme.typography.bodySmall, color = if (win) PicoTheme.colorScheme.labelPrimaryLight else JungleColors.Ink)
    }
    Spacer(Modifier.weight(1f))
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        ActionButton(t.t("再来一局", "Again"), { c.again() }, Modifier.weight(1f), icon = R.drawable.ic_play_arrow, primary = true)
        ActionButton(t.t("主菜单", "Main menu"), { c.mainMenu() }, Modifier.weight(1f), icon = R.drawable.ic_add_circle)
    }
}

@Composable
private fun ColumnScope.NewPage(s: UiState, c: GameController, t: Texts) {
    Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Title(t.t("新开一盘", "New game"))
        s.savedGame?.let { sg ->
            ActionButton(t.t("继续上一盘（${t.moveCounter(sg.plies, sg.level)}）", "Continue (${t.moveCounter(sg.plies, sg.level)})"), { c.continueSaved() }, Modifier.fillMaxWidth(), icon = R.drawable.ic_play_arrow, tag = "continue")
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(t.t("你执", "You play"), style = PicoTheme.typography.bodyLarge, color = PicoTheme.colorScheme.labelPrimary, modifier = Modifier.width(96.dp))
            Choice(t.t("红 · 先走", "Red, first"), s.draftSide == SideChoice.RED, { c.setDraft(side = SideChoice.RED) })
            Choice(t.t("蓝", "Blue"), s.draftSide == SideChoice.BLUE, { c.setDraft(side = SideChoice.BLUE) })
            Choice(t.t("随机", "Random"), s.draftSide == SideChoice.RANDOM, { c.setDraft(side = SideChoice.RANDOM) })
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(t.t("对手", "Opponent"), style = PicoTheme.typography.bodyLarge, color = PicoTheme.colorScheme.labelPrimary, modifier = Modifier.width(96.dp))
            Body(t.t("电脑", "Computer"))
        }
        Text(t.t("电脑多强", "How strong"), style = PicoTheme.typography.bodyLarge, color = PicoTheme.colorScheme.labelPrimary)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (n in 1..5) Choice(t.levelName(n), s.draftLevel == n, { c.setDraft(level = n) }, Modifier.weight(1f))
        }
        Small(t.levelBlurb(s.draftLevel))
    }
    Spacer(Modifier.height(12.dp))
    ActionButton(t.t("开始", "Start"), { c.newGame() }, Modifier.fillMaxWidth(), icon = R.drawable.ic_play_arrow, primary = true)
}

@Composable
private fun ColumnScope.RulesPage(s: UiState, c: GameController, t: Texts) {
    // everything on the first screen of the 640 dp window (critic B1): ring diagram + three cards side by side,
    // the four common slips as a list, the button
    Title(t.t("大的吃小的。老鼠例外，它能吃象。", "Big eats small. Except the rat: it eats the elephant."))
    Spacer(Modifier.height(10.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        WhoEatsWho(t, Modifier.size(196.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            RuleCard(t.t("鼠和象", "Rat and elephant"), t.t("鼠在岸上能吃象，象吃不了鼠。", "On land the rat takes the elephant. Never the other way."))
            RuleCard(t.t("狮虎跳河", "Lion and tiger leap"), t.t("沿直线跳到对岸；河里有老鼠就跳不了。", "Straight over the river. A rat in the water blocks it."))
            RuleCard(t.t("陷阱", "Traps"), t.t("进了对方的坑就是 0 级。自己的坑没事。", "In their trap you're rank 0. Your own traps are fine."))
        }
    }
    Spacer(Modifier.height(12.dp))
    Text(t.t("新手常错", "Common slips"), style = PicoTheme.typography.titleSmall, color = PicoTheme.colorScheme.labelPrimary)
    listOf(
        t.t("只有老鼠能下河。", "Only the rat swims."),
        t.t("河里的鼠谁也吃不到，它也吃不到岸上的。", "A rat in the river can't be taken, and can't take anyone on land."),
        t.t("不能走进自己的家。走进对方的家就赢。", "You can't enter your own den. Walk into theirs to win."),
        t.t("没棋走了就输。", "No moves left means you lose."),
    ).forEach { Text("· $it", style = PicoTheme.typography.bodyMedium, color = PicoTheme.colorScheme.labelPrimary, maxLines = 2, onTextLayout = overflowLog("rules/slip")) }
    Spacer(Modifier.weight(1f))
    ActionButton(t.t("开始下棋", "Let's play"), { c.rulesDone() }, Modifier.fillMaxWidth(), icon = R.drawable.ic_play_arrow, primary = true)
}

/** The "who eats who" ring of the design package (S01): eight animals by rank, arrows big -> small, rat -> elephant in red. */
@Composable
private fun WhoEatsWho(t: Texts, modifier: Modifier) {
    val ink = PicoTheme.colorScheme.labelPrimary
    val names = Animal.entries.reversed() // elephant 8 .. rat 1, clockwise from the top
    androidx.compose.foundation.Canvas(modifier) {
        val n = names.size
        val cx = size.width / 2; val cy = size.height / 2
        val r = size.minDimension / 2 - 26.dp.toPx()
        fun at(i: Int, rr: Float = r): androidx.compose.ui.geometry.Offset {
            val a = (-Math.PI / 2 + 2 * Math.PI * i / n).toFloat()
            return androidx.compose.ui.geometry.Offset(cx + rr * kotlin.math.cos(a), cy + rr * kotlin.math.sin(a))
        }
        // big -> small arrows along the ring
        for (i in 0 until n - 1) arrow(at(i, r), at(i + 1, r), ink.copy(alpha = 0.55f), 2.dp.toPx(), 22.dp.toPx())
        // the exception: rat -> elephant, red, through the inside
        arrow(at(n - 1, r), at(0, r), JungleColors.Bad, 3.dp.toPx(), 22.dp.toPx())
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            textAlign = android.graphics.Paint.Align.CENTER; textSize = 13.dp.toPx(); isFakeBoldText = true
        }
        for (i in 0 until n) {
            val p = at(i)
            drawCircle(JungleColors.Wood, radius = 19.dp.toPx(), center = p)
            drawCircle(JungleColors.Ink, radius = 19.dp.toPx(), center = p, style = androidx.compose.ui.graphics.drawscope.Stroke(1.5.dp.toPx()))
            paint.color = 0xFF1F2A1E.toInt()
            val label = if (t.zh) "${names[i].zh}${names[i].rank}" else names[i].en // full names, no letter codes (critic R4)
            paint.textSize = (if (t.zh) 13f else 7.5f).dp.toPx()
            drawContext.canvas.nativeCanvas.drawText(label, p.x, p.y + paint.textSize * 0.35f, paint)
            if (!t.zh) { paint.textSize = 10.dp.toPx(); drawContext.canvas.nativeCanvas.drawText(names[i].rank.toString(), p.x, p.y + 13.dp.toPx(), paint) }
        }
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.arrow(from: androidx.compose.ui.geometry.Offset, to: androidx.compose.ui.geometry.Offset, color: Color, width: Float, inset: Float) {
    val d = to - from
    val len = d.getDistance()
    if (len < 1f) return
    val u = d / len
    val a = from + u * inset
    val b = to - u * inset
    drawLine(color, a, b, strokeWidth = width)
    val n = androidx.compose.ui.geometry.Offset(-u.y, u.x)
    val h = width * 3.2f
    drawLine(color, b, b - u * h + n * (h * 0.6f), strokeWidth = width)
    drawLine(color, b, b - u * h - n * (h * 0.6f), strokeWidth = width)
}

@Composable
private fun RuleCard(title: String, body: String) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(PicoTheme.colorScheme.fillTertiary).padding(horizontal = 16.dp, vertical = 12.dp)) {
        Text(title, style = PicoTheme.typography.titleSmall, color = JungleColors.Ink)
        Text(body, style = PicoTheme.typography.bodyMedium, color = JungleColors.Ink)
    }
}

@Composable
private fun ColumnScope.SettingsPage(s: UiState, c: GameController, t: Texts, onExit: () -> Unit) {
    // seven rows + about + [退出应用] on the first screen of the 640 dp window (critic B1, D26); no scrolling
    val set = s.settings
    Title(t.t("设置", "Settings"))
    Spacer(Modifier.height(4.dp))
    SettingRow(t.t("语言", "Language")) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Choice(t.t("跟随系统", "System"), set.language == Language.SYSTEM, { c.updateSettings { it.copy(language = Language.SYSTEM) } })
            Choice("中文", set.language == Language.ZH, { c.updateSettings { it.copy(language = Language.ZH) } })
            Choice("English", set.language == Language.EN, { c.updateSettings { it.copy(language = Language.EN) } })
        }
    }
    SettingRow(t.t("音效", "Sound")) { SwitchCell(set.sound) { on -> c.updateSettings { it.copy(sound = on) } } }
    SettingRow(t.t("音量", "Volume")) {
        // three 48 dp chips instead of a thin slider (critic R3, D44)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(t.t("小", "Low"), t.t("中", "Medium"), t.t("大", "High")).forEachIndexed { i, label ->
                Choice(label, set.volume == i, { c.updateSettings { it.copy(volume = i) } })
            }
        }
    }
    SettingRow(t.t("减少动态", "Reduce motion")) { SwitchCell(set.reducedMotion) { on -> c.updateSettings { it.copy(reducedMotion = on) } } }
    SettingRow(t.t("一直照亮能动的子", "Always light movable pieces")) { SwitchCell(set.litAlways) { on -> c.updateSettings { it.copy(litAlways = on) } } }
    SettingRow(t.t("窗口找不到了", "Lost this window")) {
        ActionButton(t.t("重开面板", "Reopen panel"), { WindowCtl.recall() }, icon = R.drawable.ic_center_focus, tag = "recall")
    }
    Small(t.t("面板会在棋盘旁重新打开，用系统把手挪到顺手的位置。点棋盘前的席位牌也行。", "The panel reopens beside the board; move it with its system handle. Tapping the seat card does the same."))
    Spacer(Modifier.height(6.dp))
    Small(t.t("关于 · 版本 0.1.1 · 许可：Tripo 模型 / Material Icons（Apache-2.0）/ 合成音效", "About · v0.1.1 · Licences: Tripo models / Material Icons (Apache-2.0) / synthesised sounds"))
    Spacer(Modifier.weight(1f))
    if (s.confirm == Confirm.EXIT) {
        ConfirmCard(t.t("退出斗兽棋？", "Quit Jungle Chess?"), t.t("棋局会存着，下次接着下。", "Your game is saved for next time."), t.t("取消", "Cancel"), t.t("退出", "Quit"), { c.askConfirm(null) }, onExit)
    } else {
        ActionButton(t.t("退出应用", "Quit app"), { c.askConfirm(Confirm.EXIT) }, Modifier.fillMaxWidth(), icon = R.drawable.ic_logout, tag = "exit")
    }
}

/** A Switch inside a 48 dp tap cell, centred on the row (D44: measure the control, not the row). */
@Composable
private fun SwitchCell(checked: Boolean, onChange: (Boolean) -> Unit) {
    Box(Modifier.size(width = 64.dp, height = 48.dp), contentAlignment = Alignment.Center) {
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
