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
import androidx.compose.ui.input.pointer.pointerInput
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
import com.pico.spatial.ui.foundation.gesture.TargetEntity
import com.pico.spatial.ui.foundation.gesture.detectSpatialDragGesture
import com.pico.spatial.ui.foundation.gesture.detectSpatialTapGesture
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

    val scene = remember { BoardScene(onAnimationDone = { controller.animationDone(it) }, log = { Log.i(TAG, it) }) }

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
            .pointerInput(Unit) {
                detectSpatialTapGesture(context = context, targetedToEntity = TargetEntity.any()) { tap ->
                    val name = tap.targetEntity?.getName()
                    Log.i(TAG, "TAP target=$name")
                    // S10: with the control window closed, the first pinch only brings it back
                    if (!WindowCtl.controlOpen) { WindowCtl.openWindow("tap"); return@detectSpatialTapGesture }
                    val sq = scene.squareOf(name, current.game?.position)
                    if (sq >= 0) controller.tap(sq) else controller.tapNothing()
                }
            }
            .pointerInput(Unit) {
                // a drag only counts after the pointer travelled 1 cm, so a plain pinch never runs drag + tap together (critic R9)
                var pendingId = -1
                detectSpatialDragGesture(
                    context = context,
                    targetedToEntity = TargetEntity.any(),
                    onDragStart = { _, v -> pendingId = scene.pieceIdOf(v.targetEntity?.getName()) },
                    onDragEnd = { pendingId = -1; if (scene.dragging) controller.dragEnd(scene.endDrag()) },
                    onDragCancel = { pendingId = -1; if (scene.dragging) { scene.endDrag(); controller.dragEnd(-1) } },
                ) { v ->
                    val d = P3(pxToM(v.dragAmount.x), -pxToM(v.dragAmount.y), pxToM(v.dragAmount.z))
                    if (!scene.dragging && pendingId >= 0 && d.length() > 0.01f && WindowCtl.controlOpen) {
                        val sq = current.game?.position?.squareOf(pendingId) ?: -1
                        if (sq >= 0 && controller.dragStart(sq)) scene.beginDrag(pendingId)
                        pendingId = -1
                    }
                    if (scene.dragging) scene.dragTo(d)
                }
            },
        attachments = {
            AttachmentPanel(id = "seat") { SeatCard(mToDp(BoardLayout.SEAT_W), mToDp(BoardLayout.SEAT_H)) }
            AttachmentPanel(id = "countRed") { CountCard(Side.RED, mToDp(BoardLayout.COUNT_W), mToDp(BoardLayout.COUNT_H)) }
            AttachmentPanel(id = "countBlue") { CountCard(Side.BLUE, mToDp(BoardLayout.COUNT_W), mToDp(BoardLayout.COUNT_H)) }
        },
        initial = { content, attachments ->
            content.addEntity(scene.root)
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
