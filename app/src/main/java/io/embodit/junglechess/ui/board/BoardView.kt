package io.embodit.junglechess.ui.board

import android.os.SystemClock
import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pico.spatial.core.ecs.Entity
import com.pico.spatial.core.ecs.SceneUpdateContext
import com.pico.spatial.core.ecs.System
import com.pico.spatial.core.math.Vector3
import com.pico.spatial.ui.design.Text
import com.pico.spatial.ui.foundation.content.SpatialView
import com.pico.spatial.ui.foundation.dsl.registerSystem
import com.pico.spatial.ui.foundation.dsl.unregisterSystem
import com.pico.spatial.ui.platform.LengthUnit
import com.pico.spatial.ui.platform.LocalPhysicalLengthConverter
import com.pico.spatial.ui.platform.containers.LocalSpatialNavigator
import io.embodit.junglechess.domain.game.Phase
import io.embodit.junglechess.domain.model.Animal
import io.embodit.junglechess.domain.model.Side
import io.embodit.junglechess.platform.Graph
import io.embodit.junglechess.ui.theme.JungleColors
import io.embodit.junglechess.ui.theme.LocalTexts
import io.embodit.junglechess.ui.window.WindowCtl
import io.embodit.junglechess.ui.board.input.BoardInputListener
import io.embodit.junglechess.ui.board.input.InputSource
import io.embodit.junglechess.ui.board.input.Offset3M
import io.embodit.junglechess.ui.board.input.TargetResolver
import io.embodit.junglechess.ui.board.input.boardInput
import com.pico.spatial.ui.geometry.Offset3D
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext

/** Drives the scene once per rendered frame inside the ECS (never from Compose) and steps the window gate. */
class BoardFrameSystem : System() {
    override fun update(context: SceneUpdateContext) {
        BoardSceneHolder.scene?.onFrame(context.deltaTime)
        // Shared Space gives no head pose: the gate runs its no-head path (WindowCtl / LevelGate docs)
        WindowCtl.stepGate(SystemClock.elapsedRealtime(), null)
    }
}

internal object BoardSceneHolder {
    @Volatile var scene: BoardScene? = null
}

private const val TAG = "JungleChess"

/** The board volume: the 3D scene, its taps and drags, and the three small cards in front of it. */
@Composable
fun BoardView() {
    val context = LocalContext.current
    val graph = remember(context) { Graph.from(context) }
    val controller = graph.controller
    val state by controller.state.collectAsStateWithLifecycle()
    val current by rememberUpdatedState(state)
    val texts = LocalTexts.current
    val navigator = LocalSpatialNavigator.current
    val converter = LocalPhysicalLengthConverter.current
    fun pxToM(px: Float): Float = with(converter) { converter.dpToLength(px.toDp(), LengthUnit.Meters) }
    fun mToDp(m: Float): Dp = converter.lengthToDp(m, LengthUnit.Meters)

    val scene = remember { BoardScene(onAnimationDone = { controller.animationDone(it) }, log = { io.embodit.junglechess.ui.board.input.BoardInputLog.write(TAG, it) }) }

    DisposableEffect(scene, navigator) {
        BoardSceneHolder.scene = scene
        DebugHooks.scene = scene
        registerSystem<BoardFrameSystem>()
        WindowCtl.attach(navigator)
        WindowCtl.openWindow("launch")
        onDispose {
            unregisterSystem<BoardFrameSystem>()
            WindowCtl.closeAll()
            WindowCtl.detach(navigator)
            if (BoardSceneHolder.scene === scene) BoardSceneHolder.scene = null
            if (DebugHooks.scene === scene) DebugHooks.scene = null
            scene.dispose()
        }
    }

    val zh = texts.zh
    LaunchedEffect(scene) {
        if (scene.hasResources) return@LaunchedEffect
        val r = withContext(NonCancellable + Dispatchers.Default) { SceneResources.create(zh) }
        scene.provide(r)
    }
    LaunchedEffect(zh, scene.hasResources) {
        val have = scene.labelsZh() ?: return@LaunchedEffect
        if (have == zh) return@LaunchedEffect
        val (labels, plate) = withContext(Dispatchers.Default) { SceneResources.labelMaterials(zh) }
        scene.relabel(labels, plate, zh)
    }
    LaunchedEffect(state) { scene.apply(state) }

    SpatialView(
        modifier = Modifier
            .fillMaxSize()
            // D50 factory input template: hand ray, controller ray and eye-hand all arrive here (see ui/board/input)
            .boardInput(BoardTargets, object : BoardInputListener<BoardTarget> {
                override fun onTap(target: BoardTarget?, source: InputSource, position: Offset3D?) {
                    // S10: with the control window closed, the first pinch only brings it back
                    if (!WindowCtl.controlOpen) { WindowCtl.openWindow("tap"); return }
                    val sq = when (target) {
                        is BoardTarget.Square -> target.sq
                        is BoardTarget.Piece -> current.game?.position?.squareOf(target.id) ?: -1
                        BoardTarget.Board -> position?.let { scene.squareAtViewPoint(it) } ?: -1
                        null -> -1
                    }
                    io.embodit.junglechess.ui.board.input.BoardInputLog.write(TAG, "INPUT tap src=${source.label} target=$target -> square=${if (sq >= 0) io.embodit.junglechess.domain.model.Sq.name(sq) else "-"}")
                    if (sq >= 0) controller.tap(sq) else controller.tapNothing()
                }

                override fun onDragStart(target: BoardTarget, source: InputSource): Boolean {
                    val id = (target as? BoardTarget.Piece)?.id ?: return false
                    val sq = current.game?.position?.squareOf(id) ?: return false
                    if (sq < 0 || !WindowCtl.controlOpen || !controller.dragStart(sq)) return false
                    scene.beginDrag(id)
                    return true
                }

                override fun onDragMove(totalM: Offset3M) { if (scene.dragging) scene.dragTo(P3(totalM.x, totalM.y, totalM.z)) }
                override fun onDragEnd() { if (scene.dragging) controller.dragEnd(scene.endDrag()) }
                override fun onDragCancel() { if (scene.dragging) { scene.endDrag(); controller.dragEnd(-1) } }
            }),
        attachments = {
            AttachmentPanel(id = "seat") { SeatCard(mToDp(BoardLayout.SEAT_W), mToDp(BoardLayout.SEAT_H)) }
            AttachmentPanel(id = "countRed") { CountCard(Side.RED, mToDp(BoardLayout.COUNT_W), mToDp(BoardLayout.COUNT_H)) }
            AttachmentPanel(id = "countBlue") { CountCard(Side.BLUE, mToDp(BoardLayout.COUNT_W), mToDp(BoardLayout.COUNT_H)) }
        },
        initial = { content, attachments ->
            content.addEntity(scene.root)
            scene.attachContent(content)
            attachments.entity("seat")?.let { e -> e.transform().setPosition(Vector3(BoardLayout.SEAT_CARD.x, BoardLayout.SEAT_CARD.y, BoardLayout.SEAT_CARD.z)); scene.root.addChild(e) }
            for ((id, side) in listOf("countRed" to Side.RED, "countBlue" to Side.BLUE)) {
                val p = BoardLayout.countCard(side)
                attachments.entity(id)?.let { e -> e.transform().setPosition(Vector3(p.x, p.y, p.z)); scene.root.addChild(e) }
            }
            // static models first (the base discs are the S00 placeholders), then the eight animals
            val (board, base, tray) = coroutineScope {
                listOf("board", "base", "tray").map { n ->
                    async { runCatching { Entity.loadSuspend("asset://models/$n.glb") }.onFailure { Log.e(TAG, "model $n failed", it) }.getOrNull() }
                }.awaitAll()
            }
            scene.installStatic(board, base, tray)
            val animals = coroutineScope {
                Animal.entries.map { a ->
                    async { a to runCatching { Entity.loadSuspend("asset://models/animals/${a.asset}.glb") }.onFailure { Log.e(TAG, "animal ${a.asset} failed", it) }.getOrNull() }
                }.awaitAll()
            }
            val ok = animals.filter { it.second != null }.associate { it.first to it.second!! }
            ok.values.forEach { it.enabled = false; content.addEntity(it) }
            scene.installAnimals(ok)
            controller.modelsLoaded(ok.size == Animal.entries.size && board != null && base != null)
        },
        update = { _, _ -> },
    )
}

@Composable
private fun SeatCard(w: Dp, h: Dp) {
    val context = LocalContext.current
    val c = remember(context) { Graph.from(context).controller }
    val s by c.state.collectAsStateWithLifecycle()
    val t = LocalTexts.current
    val g = s.game
    val text = t.seatCard(s.phase == Phase.THINKING, g?.human ?: Side.RED, g?.position?.toMove, g?.result != null)
    // a pinch on the seat card brings the control window back beside the board (an entry outside the panel, critic R2)
    Box(
        Modifier.size(w, h).clip(RoundedCornerShape(h / 3)).background(JungleColors.Ink)
            .clickable { Log.i(TAG, "SEAT tapped -> window"); if (WindowCtl.controlOpen) WindowCtl.recall() else WindowCtl.openWindow("seat") },
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = JungleColors.Paper, fontSize = (h.value * 0.34f).sp, textAlign = TextAlign.Center, maxLines = 1, onTextLayout = { r -> if (r.hasVisualOverflow) Log.w(TAG, "OVERFLOW seatCard/$text") })
    }
}

@Composable
private fun CountCard(capturer: Side, w: Dp, h: Dp) {
    val context = LocalContext.current
    val c = remember(context) { Graph.from(context).controller }
    val s by c.state.collectAsStateWithLifecycle()
    val n = s.game?.capturedBy(capturer)?.size ?: 0
    Box(Modifier.size(w, h).clip(RoundedCornerShape(h / 3)).background(if (capturer == Side.RED) JungleColors.Red else JungleColors.Blue), contentAlignment = Alignment.Center) {
        Text(n.toString(), color = JungleColors.Paper, fontSize = (h.value * 0.75f).sp, textAlign = TextAlign.Center, maxLines = 1)
    }
}

/** Hooks for the debug broadcast (debug build only uses them). */
internal object DebugHooks {
    @Volatile var scene: BoardScene? = null
}

/** What a pinch / trigger can land on: a square pad, a piece (any of its meshes), or the board surface. */
sealed interface BoardTarget {
    data class Square(val sq: Int) : BoardTarget
    data class Piece(val id: Int) : BoardTarget
    data object Board : BoardTarget
}

/** Entity names set by BoardScene: `sq:<n>` pads, `piece:<id>` holders (base / animal meshes hang below), `terrain`. */
object BoardTargets : TargetResolver<BoardTarget> {
    override fun resolve(entity: Entity): BoardTarget? {
        val n = entity.getName()
        return when {
            n.startsWith("sq:") -> n.removePrefix("sq:").toIntOrNull()?.let { BoardTarget.Square(it) }
            n.startsWith("piece:") || n.startsWith("hit:") -> n.substringAfter(':').toIntOrNull()?.let { BoardTarget.Piece(it) }
            n == "terrain" -> BoardTarget.Board
            else -> null
        }
    }
}
