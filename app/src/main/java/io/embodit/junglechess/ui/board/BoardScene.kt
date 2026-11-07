package io.embodit.junglechess.ui.board

import android.util.Log
import com.pico.spatial.core.ecs.CollisionComponent
import com.pico.spatial.core.ecs.DirectionalLightComponent
import com.pico.spatial.core.ecs.Entity
import com.pico.spatial.core.ecs.GroundShadowComponent
import com.pico.spatial.core.ecs.HoverEffectComponent
import com.pico.spatial.core.ecs.InteractableComponent
import com.pico.spatial.core.ecs.ModelComponent
import com.pico.spatial.core.ecs.ModelEntity
import com.pico.spatial.core.ecs.TransformComponent
import com.pico.spatial.core.ecs.resource.Material
import com.pico.spatial.core.ecs.resource.MeshResource
import com.pico.spatial.core.math.Color4
import com.pico.spatial.core.math.Quat
import com.pico.spatial.core.math.Vector3
import io.embodit.junglechess.domain.game.Anim
import io.embodit.junglechess.domain.game.MotionPlan
import io.embodit.junglechess.domain.game.UiState
import io.embodit.junglechess.domain.model.Animal
import io.embodit.junglechess.domain.model.Pieces
import io.embodit.junglechess.domain.model.Position
import io.embodit.junglechess.domain.model.Side
import io.embodit.junglechess.domain.model.Sq
import io.embodit.junglechess.domain.model.Terrain
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

internal fun Entity.transform(): TransformComponent =
    components[TransformComponent::class.java] ?: TransformComponent().also { components.set(it) }

private fun v(p: P3) = Vector3(p.x, p.y, p.z)

/**
 * Every entity of the jungle board and the animations of committed moves.
 *
 * ```
 * root (volume space: origin = volume centre, +Z toward the user)
 * ├ light (warm directional key light)
 * ├ boardRoot (tilted 20° about the near bottom edge; board space)
 * │ ├ board.glb (turf, soil, river, traps, dens), coordinates, den plates
 * │ ├ 63 pads "sq:<n>" (invisible tap targets, D17)
 * │ └ marks (pooled flat planes: selection, legal dots, capture / leap frames, hint, last move, alerts, lit rings)
 * ├ 16 pieces "piece:<id>" (volume space): base.glb clone + animal GLB clone, collider child "hit:<id>"
 * └ trays (tray.glb x 2), attachment cards (added by BoardView)
 * ```
 * Main thread only: [apply] from Compose, [onFrame] from the ECS system.
 */
internal class BoardScene(private val onAnimationDone: (Int) -> Unit, private val log: (String) -> Unit) {
    val root: Entity = Entity().apply { setName("sceneRoot") }
    private val boardRoot: Entity = Entity().apply { setName("boardRoot") }

    private var res: SceneResources? = null
    private var boardModel: Entity? = null
    private var baseModel: Entity? = null
    private var trayModel: Entity? = null
    private val animalModels = HashMap<Animal, Entity>()
    private var built = false
    private var animalsInstalled = false

    private inner class PieceView(val id: Int) {
        // v0.1.3 bug: inside `Entity().apply {}` a bare `id` is Entity.id (the ECS entity id, e.g. 122), not the
        // piece index -> taps resolved to Piece(122) -> no square. Names come from BoardNames with the piece index.
        val holder = Entity().apply { setName(BoardNames.piece(this@PieceView.id)) }
        val hit = Entity().apply { setName(BoardNames.hit(this@PieceView.id)) }
        /** Visible meshes (base, animal) hang under the collider so the system hover lights them (D51 hover rule). */
        val vis = Entity().apply { setName(BoardNames.vis(this@PieceView.id)) }
        var hittable = true
        var base: Entity? = null
        var animal: Entity? = null
        var ringPart: Entity? = null
        var topPart: Entity? = null
        var pos = P3(0f, 0f, 0f)
        var rot = Quat()
        var scale = 1f
        var glideFrom: P3? = null
        var glideTo: P3? = null
        var glideT = 0f
        var trapped = false
        var inTray = false
        fun place(p: P3, q: Quat, s: Float) {
            pos = p; rot = q; scale = s
            holder.transform().setPosition(v(p)).setQuaternion(q).setScaleVector(Vector3(s, s, s))
        }
    }

    private val pieces = arrayOfNulls<PieceView>(Pieces.COUNT)
    private val pads = HashMap<String, Int>()

    private class Mark(val entity: ModelEntity) { var used = false }
    private val pools = HashMap<String, MutableList<Mark>>()
    private var ripple: ModelEntity? = null
    private var rippleT = -1f
    private var denPlates = ArrayList<ModelEntity>()
    private var testCard: ModelEntity? = null
    private var light: Entity? = null

    private var state: UiState? = null
    private var playingToken = -1
    private var timeline: Timeline? = null
    private var clock = 0f
    private var alertClock = 0f
    private var flashClock = 0f
    private var dragId = -1
    private var dragStart = P3(0f, 0f, 0f)
    var paused = false
    var showTest = false
        set(value) { field = value; testCard?.enabled = value }

    val hasResources get() = res != null

    private var content: com.pico.spatial.core.container.SpatialViewContent? = null
    fun attachContent(c: com.pico.spatial.core.container.SpatialViewContent) { content = c }

    /**
     * A hit point in the SpatialView's local px space -> the square under it (board-surface hits that did not land on a
     * pad collider, e.g. eye-hand on the turf mesh). -1 when it is not over a square.
     */
    fun squareAtViewPoint(p: com.pico.spatial.ui.geometry.Offset3D): Int {
        val c = content ?: return -1
        val v = runCatching {
            c.convertPosition(Vector3(p.x, p.y, p.z), com.pico.spatial.core.ecs.ViewCoordinateSpace.Local, c.localSpatialCoordinateSpace)
        }.getOrElse { log("INPUT convert failed $it"); return -1 }
        val b = BoardLayout.toBoard(P3(v.x, v.y, v.z))
        val col = Math.round(b.x / BoardLayout.PITCH + 3f)
        val row = Math.round(b.z / BoardLayout.PITCH + 5f)
        val ok = col in 0 until Sq.COLS && row in 1..Sq.ROWS && kotlin.math.abs(b.y) < 0.03f
        log("INPUT board point view=(${"%.0f".format(p.x)}, ${"%.0f".format(p.y)}, ${"%.0f".format(p.z)}) volume=(${"%.3f".format(v.x)}, ${"%.3f".format(v.y)}, ${"%.3f".format(v.z)}) board=(${"%.3f".format(b.x)}, ${"%.3f".format(b.y)}, ${"%.3f".format(b.z)}) -> ${if (ok) Sq.name(Sq.of(col, row)) else "off"}")
        return if (ok) Sq.of(col, row) else -1
    }

    // ------------------------------------------------------------------ build

    fun provide(r: SceneResources) {
        if (res == null) res = r else r.close()
        tryBuild()
    }

    fun installStatic(board: Entity?, base: Entity?, tray: Entity?) {
        boardModel = board; baseModel = base; trayModel = tray
        tryBuild()
    }

    fun installAnimals(models: Map<Animal, Entity>) {
        animalModels.putAll(models)
        if (built) attachAnimals()
    }

    fun ready(): Boolean = built

    private fun tryBuild() {
        val r = res ?: return
        if (built || baseModel == null) return
        built = true
        val t0 = android.os.SystemClock.elapsedRealtime()
        root.addChild(boardRoot)
        boardRoot.transform().setPosition(v(BoardLayout.toVolume(P3(0f, 0f, 0f)))).setQuaternion(Quat(Vector3(1f, 0f, 0f), BoardLayout.TILT))
        boardModel?.let { it.setName(BoardNames.TERRAIN); boardRoot.addChild(it) }
        // warm key light from upper left behind the user (B24: natural, not flat, not blown out)
        light = Entity().apply {
            setName("keyLight")
            runCatching { components.set(DirectionalLightComponent(Color4(1f, 0.94f, 0.84f, 1f), 1.2f, true)) }
                .onFailure { log("LIGHT directional failed: $it") }
            transform().setQuaternion(Quat(Vector3(0f, 1f, 0f), (-0.5).toFloat()) * Quat(Vector3(1f, 0f, 0f), (-0.95).toFloat()))
        }
        root.addChild(light!!)
        // tap pads: one thin box per square (D17)
        for (sq in 0 until Sq.COUNT) {
            val p = BoardLayout.square(sq)
            val pad = Entity().apply {
                setName(BoardNames.square(sq))
                transform().setPosition(Vector3(p.x, 0.002f, p.z))
                components.set(CollisionComponent(listOf(r.padShape), r.physics))
                components.set(InteractableComponent())
                components.set(HoverEffectComponent())
            }
            // visible marker under the collider: the system hover is drawn on this mesh (an invisible pad shows nothing)
            ModelEntity(r.padDot, if (Terrain.isRiver(sq)) r.padDotWater else r.padDotLand).apply {
                setName(BoardNames.dot(sq))
                transform().setPosition(Vector3(0f, markY(sq) + 0.0012f - 0.002f, 0f))
                    .setQuaternion(Quat(Vector3(1f, 0f, 0f), (-PI / 2).toFloat()))
                pad.addChild(this)
            }
            boardRoot.addChild(pad)
            pads[BoardNames.square(sq)] = sq
        }
        // coordinates on the soil border: files along the near edge, ranks along the left edge
        // files along the far border: the seat card in front would hide a near-border strip from the seat
        addFlat(r.filesMesh, r.filesMat, P3(0f, 0.0004f, -BoardLayout.HALF_D + 0.02f), "files")
        addFlat(r.ranksMesh, r.ranksMat, P3(-BoardLayout.HALF_W + 0.02f, 0.0004f, 0f), "ranks")
        // den plates on the lintels (face +Z = toward the user for both dens)
        for (side in Side.entries) {
            val den = BoardLayout.square(Terrain.den(side))
            val outer = -1f // lintel on the far side of both dens (critic R6)
            val z = den.z + outer * (0.0295f - 0.003f) + 0.0046f
            val plate = ModelEntity(r.denPlateMesh, r.denPlate!!).apply {
                setName("denPlate:$side")
                transform().setPosition(Vector3(den.x, 0.0005f + 0.0165f, z))
            }
            boardRoot.addChild(plate)
            denPlates += plate
        }
        testCard = addFlat(r.markCell, r.testMat, P3(BoardLayout.colX(3), 0.003f, BoardLayout.rowZ(7)), "ftest").also { it.enabled = showTest }
        ripple = addFlat(r.markRing, r.ripple, P3(0f, -0.0055f, 0f), "ripple").also { it.enabled = false }
        // trays
        trayModel?.let { t ->
            for (side in Side.entries) {
                val tray = if (side == Side.RED) t else t.clone(Entity.CloneOptions(true, true)) ?: continue
                tray.setName("tray:$side")
                tray.transform().setPosition(v(BoardLayout.trayCenter(side)))
                root.addChild(tray)
            }
        }
        // pieces: base discs first (the S00 placeholders), animals join when their GLBs are in
        for (id in 0 until Pieces.COUNT) {
            val pv = PieceView(id)
            val base = baseModel!!.let { if (id == 0) it else it.clone(Entity.CloneOptions(true, false)) } ?: continue
            base.setName("base:$id")
            pv.base = base
            pv.vis.addChild(base)
            paintBase(pv, r)
            val h = BoardLayout.pieceColliderHeight(id)
            pv.hit.transform().setPosition(Vector3(0f, h / 2f, 0f))
            pv.vis.transform().setPosition(Vector3(0f, -h / 2f, 0f))
            pv.hit.addChild(pv.vis)
            pv.hit.components.set(CollisionComponent(listOf(r.pieceShape.getValue(Pieces.animal(id))), r.physics))
            pv.hit.components.set(InteractableComponent())
            pv.hit.components.set(HoverEffectComponent())
            pv.holder.addChild(pv.hit)
            // rank tag on the user's edge of the base, upright for the seat (v1 is human vs AI: both teams face the user's eye)
            val red = Pieces.side(id) == Side.RED
            ModelEntity(r.tagMesh, r.rankTags.getValue(id)).apply {
                setName("tag:$id")
                transform().setPosition(Vector3(0f, BoardLayout.TAG_Y, if (red) BoardLayout.TAG_Z else -BoardLayout.TAG_Z))
                    .setQuaternion((if (red) Quat() else Quat(Vector3(0f, 1f, 0f), PI.toFloat())) * Quat(Vector3(1f, 0f, 0f), Math.toRadians(BoardLayout.TAG_TILT_DEG.toDouble()).toFloat()))
                pv.holder.addChild(this)
            }
            pv.holder.enabled = false
            root.addChild(pv.holder)
            pieces[id] = pv
        }
        log("SCENE built in ${android.os.SystemClock.elapsedRealtime() - t0} ms")
        if (animalModels.isNotEmpty()) attachAnimals()
        state?.let { apply(it, force = true) }
    }

    private fun attachAnimals() {
        if (animalsInstalled) return
        animalsInstalled = true
        for (id in 0 until Pieces.COUNT) {
            val pv = pieces[id] ?: continue
            val template = animalModels[Pieces.animal(id)] ?: continue
            val model = if (Pieces.side(id) == Side.RED) template else template.clone(Entity.CloneOptions(true, true)) ?: continue
            model.setName("animal:$id:${Pieces.animal(id).asset}")
            model.enabled = true
            model.transform().setPosition(Vector3(0f, BoardLayout.BASE_H, 0f))
            runCatching { model.components.set(GroundShadowComponent(true, false)) }
            pv.animal = model
            pv.vis.addChild(model)
        }
        log("ANIMALS attached ${animalModels.size} models")
    }

    /** Team colour on the body, gold (or grey in a trap) rank ring, the piece's label on top. */
    private fun paintBase(pv: PieceView, r: SceneResources) {
        fun visit(e: Entity) {
            val n = e.getName()
            val mats = e.components[ModelComponent::class.java]?.materials
            if (mats != null && mats.iterator().hasNext()) when {
                n.startsWith("BaseBody") -> mats[0] = r.teamBody.getValue(Pieces.side(pv.id))
                n.startsWith("BaseRing") -> { mats[0] = if (pv.trapped) r.ringGrey else r.ringGold; pv.ringPart = e }
                n.startsWith("BaseTop") -> { r.labels[pv.id]?.let { mats[0] = it }; pv.topPart = e }
            }
            e.getChildren().forEach { visit(it) }
        }
        pv.base?.let { visit(it) }
    }

    /** Language switch: new base-top labels and den plates. */
    fun relabel(labels: Map<Int, Material>, plate: Material, zh: Boolean) {
        val r = res ?: return
        r.labels = labels
        r.denPlate = plate
        r.labelsZh = zh
        pieces.forEach { pv -> pv?.topPart?.components?.get(ModelComponent::class.java)?.materials?.let { it[0] = labels.getValue(pv.id) } }
        denPlates.forEach { it.components[ModelComponent::class.java]?.materials?.let { m -> m[0] = plate } }
        log("LABELS rebuilt zh=$zh")
    }

    fun labelsZh(): Boolean? = res?.labelsZh

    private fun addFlat(mesh: MeshResource, mat: Material, at: P3, name: String, parent: Entity = boardRoot): ModelEntity {
        val e = ModelEntity(mesh, mat).apply {
            setName(name)
            transform().setPosition(v(at)).setQuaternion(Quat(Vector3(1f, 0f, 0f), (-PI / 2).toFloat()))
        }
        parent.addChild(e)
        return e
    }

    // ------------------------------------------------------------------ input mapping

    /** Square a tap target stands for: a pad, or the square of the piece hit. -1 = nothing on the board. */
    fun squareOf(entityName: String?, pos: Position?): Int {
        if (entityName == null) return -1
        pads[entityName]?.let { return it }
        val id = pieceIdOf(entityName)
        if (id >= 0 && pos != null) return pos.squareOf(id)
        return -1
    }

    fun pieceIdOf(name: String?): Int {
        if (name == null) return -1
        val m = Regex("^(?:hit|piece|base|animal):(\\d+)").find(name) ?: return -1
        return m.groupValues[1].toInt()
    }

    // ------------------------------------------------------------------ state -> entities

    fun apply(s: UiState, force: Boolean = false) {
        val prev = state
        state = s
        if (!built) return
        val game = s.game
        // a new committed move: play it
        val anim = s.anim
        if (anim != null && anim.token != playingToken) {
            playingToken = anim.token
            startAnimation(anim, s)
        }
        if (timeline == null && dragId < 0) settlePieces(s, glide = !force && prev?.game?.position != game?.position)
        updateMarks(s)
        if (prev?.flash?.token != s.flash?.token) flashClock = 0f
        if (prev?.denThreat != s.denThreat) alertClock = 0f
    }

    private fun quat(side: Side, pitchDeg: Float, onBoard: Boolean): Quat {
        val yaw = Quat(Vector3(0f, 1f, 0f), BoardLayout.yaw(side))
        val pitch = if (pitchDeg != 0f) Quat(Vector3(1f, 0f, 0f), Math.toRadians(pitchDeg.toDouble()).toFloat()) else Quat()
        val tilt = if (onBoard) Quat(Vector3(1f, 0f, 0f), BoardLayout.TILT) else Quat()
        return tilt * yaw * pitch
    }

    /** Rest pose of every piece for the state (board squares, tray slots for the captured). */
    private fun settlePieces(s: UiState, glide: Boolean) {
        val game = s.game
        val r = res ?: return
        // no game yet (first run: rules / new-game page): show the opening set up (design S01, QA k2)
        val pos = game?.position ?: io.embodit.junglechess.domain.model.Position.start()
        val captured = game?.captured ?: emptyList()
        val trayIndex = HashMap<Side, Int>()
        for (id in captured) {
            val pv = pieces[id] ?: continue
            val capturer = Pieces.side(id).other
            val k = trayIndex.getOrDefault(capturer, 0)
            trayIndex[capturer] = k + 1
            pv.inTray = true
            setHittable(pv, false)
            pv.holder.enabled = true
            pv.glideTo = null
            pv.place(BoardLayout.traySlot(capturer, k), Quat(Vector3(0f, 1f, 0f), PI.toFloat()), BoardLayout.TRAY_SCALE)
        }
        for (id in 0 until Pieces.COUNT) {
            val pv = pieces[id] ?: continue
            if (id in captured) continue
            val sq = pos.squareOf(id)
            if (sq < 0) { pv.holder.enabled = false; continue }
            val side = Pieces.side(id)
            pv.inTray = false
            setHittable(pv, true)
            val trapped = BoardLayout.inEnemyTrap(sq, side)
            if (trapped != pv.trapped) {
                pv.trapped = trapped
                pv.ringPart?.components?.get(ModelComponent::class.java)?.materials?.let { it[0] = if (trapped) r.ringGrey else r.ringGold }
            }
            val lift = if (s.selected == sq) BoardLayout.LIFT_SELECTED else 0f
            val target = BoardLayout.toVolume(BoardLayout.rest(sq, side) + P3(0f, lift, 0f))
            val q = quat(side, if (trapped && !s.settings.reducedMotion) BoardLayout.TRAP_PITCH_DEG else 0f, true)
            if (glide && pv.holder.enabled && (pv.pos - target).length() > 0.002f && !s.settings.reducedMotion) {
                pv.glideFrom = pv.pos; pv.glideTo = target; pv.glideT = 0f
                pv.rot = q; pv.holder.transform().setQuaternion(q)
                pv.scale = 1f; pv.holder.transform().setScaleVector(Vector3(1f, 1f, 1f))
            } else {
                pv.glideTo = null
                pv.place(target, q, 1f)
            }
            pv.holder.enabled = true
        }
    }

    // ------------------------------------------------------------------ move animation

    private fun startAnimation(anim: Anim, s: UiState) {
        timeline?.finish()
        val move = anim.move
        val plan = MotionPlan.of(move, anim.reduced)
        val mover = pieces[move.piece] ?: return finishSoon(anim.token)
        val side = Pieces.side(move.piece)
        val from = BoardLayout.rest(move.from, side)
        val to = BoardLayout.rest(move.to, side)
        val q = quat(side, 0f, true)
        val apex = when { anim.reduced -> 0f; plan.kind == MotionPlan.Kind.JUMP -> MotionPlan.JUMP_APEX; else -> MotionPlan.LIFT }
        val clips = ArrayList<Clip>()
        clips += Clip(0f, plan.travel, onStart = { mover.glideTo = null; mover.place(mover.pos, q, 1f) }, update = { u ->
            val e = if (plan.kind == MotionPlan.Kind.JUMP) u else Easing.inOut(u)
            var p = from.lerp(to, e)
            // rat toppling the elephant stops at the elephant's feet
            if (plan.kind == MotionPlan.Kind.RAT_TOPPLES_ELEPHANT) p = from.lerp(to, e * 0.82f)
            p += P3(0f, apex * Easing.hump(u), 0f)
            mover.place(BoardLayout.toVolume(p), q, 1f)
        })
        if (plan.kind == MotionPlan.Kind.RAT_TOPPLES_ELEPHANT && !anim.reduced) {
            clips += Clip(plan.travel, 0.12f, update = { u -> mover.place(BoardLayout.toVolume(from.lerp(to, 0.82f + 0.18f * u)), q, 1f) })
        }
        if (plan.intoRiver && !anim.reduced) {
            clips += Clip(plan.travel, plan.settle, onStart = { startRipple(move.to) }, update = { })
        }
        if (plan.intoTrap && !anim.reduced) {
            val tq = quat(side, BoardLayout.TRAP_PITCH_DEG, true)
            clips += Clip(plan.travel, plan.settle, update = { u -> mover.place(BoardLayout.toVolume(to + P3(0f, 0f, 0f)), Quat.slerp(q, tq, u), 1f) })
        }
        if (move.captured >= 0) {
            val victim = pieces[move.captured]
            if (victim != null) {
                val vSide = Pieces.side(move.captured)
                val vq = quat(vSide, 0f, true)
                val topple = plan.kind == MotionPlan.Kind.RAT_TOPPLES_ELEPHANT
                val vStart = victim.pos
                clips += Clip(plan.captureDelay, plan.captureDuration, update = { u ->
                    if (anim.reduced) { victim.place(vStart, vq, 1f - 0.5f * u); return@Clip }
                    if (topple) {
                        val back = Quat(Vector3(1f, 0f, 0f), Math.toRadians(35.0 * Easing.out(u)).toFloat())
                        victim.place(vStart, vq * back, 1f - 0.4f * u)
                    } else {
                        victim.place(vStart + P3(0f, 0.01f * Easing.hump(u), 0f), vq, 1f - 0.5f * u)
                    }
                }, onEnd = {
                    val capturer = vSide.other
                    val k = (s.game?.captured?.count { Pieces.side(it) == vSide } ?: 1) - 1
                    victim.inTray = true
                    setHittable(victim, false)
                    victim.place(BoardLayout.traySlot(capturer, k.coerceAtLeast(0)), Quat(Vector3(0f, 1f, 0f), PI.toFloat()), BoardLayout.TRAY_SCALE)
                })
            }
        }
        clock = 0f
        log("ANIM start token=${anim.token} ${move.notation()} kind=${plan.kind} total=${plan.total}s reduced=${anim.reduced}")
        timeline = Timeline(clips, tail = 0.02f) {
            timeline = null
            log("ANIM done token=${anim.token}")
            onAnimationDone(anim.token)
        }
    }

    private fun finishSoon(token: Int) { onAnimationDone(token) }

    private fun startRipple(sq: Int) {
        val e = ripple ?: return
        val p = BoardLayout.square(sq)
        e.transform().setPosition(Vector3(p.x, -0.0052f, p.z))
        e.enabled = true
        rippleT = 0f
    }

    // ------------------------------------------------------------------ marks

    private fun pool(key: String, mesh: MeshResource, mat: Material): MutableList<Mark> = pools.getOrPut(key) { ArrayList() }

    private fun take(key: String, mesh: MeshResource, mat: Material, sq: Int, lift: Float = 0f, scale: Float = 1f): ModelEntity {
        val list = pool(key, mesh, mat)
        val m = list.firstOrNull { !it.used } ?: Mark(ModelEntity(mesh, mat).apply {
            setName("mark:$key")
            transform().setQuaternion(Quat(Vector3(1f, 0f, 0f), (-PI / 2).toFloat()))
            boardRoot.addChild(this)
        }).also { list += it }
        m.used = true
        val p = BoardLayout.square(sq)
        val y = markY(sq) + lift
        m.entity.transform().setPosition(Vector3(p.x, y, p.z)).setScaleVector(Vector3(scale, scale, scale))
        m.entity.enabled = true
        return m.entity
    }

    private fun markY(sq: Int): Float = when {
        Terrain.isRiver(sq) -> -0.0054f
        Terrain.denOwner(sq) != null -> 0.0042f
        Terrain.trapOwner(sq) != null -> 0.0028f
        else -> 0.0024f
    }

    private fun updateMarks(s: UiState) {
        val r = res ?: return
        pools.values.forEach { l -> l.forEach { it.used = false } }
        val game = s.game
        if (game != null) {
            val pos = game.position
            val animating = s.anim != null
            if (!animating) {
                game.lastMove?.let { take("last", r.markCell, r.lastFrame, it.from); take("last", r.markCell, r.lastFrame, it.to, 0.0002f) }
                for (sq in s.lit) take("lit", r.markRing, r.litRing, sq, 0.0003f)
                if (s.selected >= 0) take("sel", r.markRing, r.selRing, s.selected, 0.0004f)
                for (m in s.targets) when {
                    m.isJump -> take("jump", r.markCell, r.jumpFrame, m.to, 0.0005f)
                    m.captured >= 0 -> take("cap", r.markCell, r.captureFrame, m.to, 0.0005f)
                    else -> take("dot", r.markDot, r.legalDot, m.to, 0.0019f)
                }
                for (sq in s.blockers) {
                    // a red cross standing over the rat's head, facing the user
                    val e = take("cross", r.markCross, r.crossMat, sq)
                    val p = BoardLayout.square(sq)
                    e.transform().setPosition(Vector3(p.x, BoardLayout.RIVER_SINK + BoardLayout.BASE_H + BoardLayout.animalHeight(Animal.RAT) + 0.004f, p.z)).setQuaternion(Quat())
                }
                s.hint?.let { take("hintRing", r.markRing, r.hintRing, it.from, 0.0006f); take("hintFrame", r.markCell, r.hintFrame, it.to, 0.0006f) }
                for (sq in s.denThreat) take("alertRing", r.markRing, r.alertRing, sq, 0.0007f)
                if (s.denThreat.isNotEmpty()) take("alertFrame", r.markCell, r.alertFrame, io.embodit.junglechess.domain.model.Terrain.den(game.human), 0.0007f)
            }
            s.flash?.let { take("bad", r.markCell, r.badFrame, it.square, 0.0008f) }
            if (pos.toMove == game.human || animating) Unit
        }
        pools.values.forEach { l -> l.forEach { if (!it.used) it.entity.enabled = false } }
    }

    // ------------------------------------------------------------------ drag (D27 forgiving)

    fun beginDrag(id: Int) {
        val pv = pieces[id] ?: return
        dragId = id
        dragStart = pv.pos
        pv.glideTo = null
    }

    /** [delta]: pointer movement since the drag started, metres in volume space. */
    fun dragTo(delta: P3) {
        val pv = pieces[dragId] ?: return
        val side = Pieces.side(dragId)
        val p = dragStart + delta
        // keep the piece 3 cm above the board while it follows the pointer
        val b = BoardLayout.toBoard(p)
        val held = BoardLayout.toVolume(P3(b.x, maxOf(b.y, 0f) + BoardLayout.LIFT_MOVE, b.z))
        pv.place(held, quat(side, 0f, true), 1f)
    }

    /** Nearest legal target within 0.75 pitch of where the piece hangs, or -1 (springs back). */
    fun endDrag(): Int {
        val id = dragId
        dragId = -1
        val pv = pieces[id] ?: return -1
        val s = state ?: return -1
        val b = BoardLayout.toBoard(pv.pos)
        var best = -1
        var bestD = BoardLayout.PITCH * 0.75f
        for (m in s.targets) {
            val c = BoardLayout.square(m.to)
            val d = kotlin.math.hypot(c.x - b.x, c.z - b.z)
            if (d < bestD) { bestD = d; best = m.to }
        }
        log("DRAG end at board (${"%.3f".format(b.x)}, ${"%.3f".format(b.z)}) -> ${if (best >= 0) Sq.name(best) else "back"}")
        if (best < 0) state?.let { settlePieces(it, glide = true) }
        return best
    }

    val dragging: Boolean get() = dragId >= 0

    // ---- hover (D50): a faint frame on the square under the gaze / ray; expires 250 ms after the last update ----
    private var hoverMark: ModelEntity? = null
    private var hoverSq = -1
    private var hoverAge = 0f

    /** No exit event is guaranteed: hold the frame 1.5 s after the last hover (template "Hover 持续"), a new target moves it at once. */
    private val HOVER_HOLD = 1.5f

    private fun setHittable(pv: PieceView, on: Boolean) {
        if (pv.hittable == on) return
        pv.hittable = on
        val r = res ?: return
        if (on) pv.hit.components.set(CollisionComponent(listOf(r.pieceShape.getValue(Pieces.animal(pv.id))), r.physics))
        else pv.hit.components.remove(CollisionComponent::class.java)
    }

    /** Debug replay (v0.1.4): the real scene entities a ray can land on for [sq] -- pad marker, and the piece's deepest mesh. */
    fun replayEntities(sq: Int, pos: Position?): List<Entity> {
        val out = ArrayList<Entity>()
        fun deepest(e: Entity): Entity { var x = e; while (x.getChildren().iterator().hasNext()) x = x.getChildren().first(); return x }
        boardRoot.getChildren().firstOrNull { it.getName() == BoardNames.square(sq) }?.let { pad -> out += pad; pad.getChildren().firstOrNull()?.let { out += it } }
        val id = pos?.at(sq) ?: -1
        pieces.getOrNull(id)?.let { pv -> out += pv.hit; pv.animal?.let { out += deepest(it) }; pv.base?.let { out += deepest(it) } }
        return out
    }

    fun setHover(sq: Int) {
        hoverAge = 0f
        if (sq == hoverSq) return
        hoverSq = sq
        val r = res ?: return
        val e = hoverMark ?: ModelEntity(r.markCell, r.hoverFrame).apply {
            setName("mark:hover")
            transform().setQuaternion(Quat(Vector3(1f, 0f, 0f), (-PI / 2).toFloat()))
            boardRoot.addChild(this)
        }.also { hoverMark = it }
        if (sq < 0) { e.enabled = false; return }
        val p = BoardLayout.square(sq)
        e.transform().setPosition(Vector3(p.x, markY(sq) + 0.0009f, p.z))
        e.enabled = true
    }

    // ------------------------------------------------------------------ per frame

    fun onFrame(dt: Float) {
        if (!built) return
        val step = dt.coerceIn(0f, 0.1f)
        if (!paused) timeline?.advance(step)
        clock += step
        // glides (undo / redo / restore / spring back), 180 ms
        for (pv in pieces) {
            pv ?: continue
            val to = pv.glideTo ?: continue
            val from = pv.glideFrom ?: continue
            pv.glideT = (pv.glideT + step / 0.18f).coerceAtMost(1f)
            val p = from.lerp(to, Easing.inOut(pv.glideT))
            pv.pos = p
            pv.holder.transform().setPosition(v(p))
            if (pv.glideT >= 1f) pv.glideTo = null
        }
        if (hoverSq >= 0) { hoverAge += step; if (hoverAge > HOVER_HOLD) setHover(-1) }
        // ripple: expand and fade over 400 ms
        if (rippleT >= 0f) {
            rippleT += step
            val u = (rippleT / MotionPlan.RIPPLE).coerceAtMost(1f)
            val k = 0.5f + 0.9f * u
            ripple?.transform()?.setScaleVector(Vector3(k, k, k))
            res?.ripple?.setOpacity(1f - u)
            if (u >= 1f) { ripple?.enabled = false; rippleT = -1f }
        }
        // den alarm: the frame blinks three times, then stays
        val s = state
        if (s != null && s.denThreat.isNotEmpty()) {
            alertClock += step
            val on = alertClock > 1.5f || ((alertClock / 0.25f).toInt() % 2 == 0)
            pools["alertFrame"]?.forEach { if (it.used) it.entity.enabled = on }
        }
        // illegal flash: visible for its 300 ms (the controller clears it)
        if (s?.flash != null) {
            flashClock += step
            val on = (flashClock / 0.1f).toInt() % 2 == 0
            pools["bad"]?.forEach { if (it.used) it.entity.enabled = on }
        }
    }

    /** Debug `dump`: where every piece stands in volume space. */
    fun dump(): String = pieces.filterNotNull().joinToString("; ") { pv ->
        "${pv.id}:${Pieces.animal(pv.id).en}@(%.3f,%.3f,%.3f)s=%.2f%s%s".format(pv.pos.x, pv.pos.y, pv.pos.z, pv.scale, if (pv.inTray) " tray" else "", if (pv.holder.enabled) "" else " hidden")
    }

    fun setLight(intensity: Float) {
        light?.components?.get(DirectionalLightComponent::class.java)?.intensity = intensity
        log("LIGHT intensity=$intensity")
    }

    fun dispose() {
        timeline = null
        runCatching { root.destroy(true) }
        res?.close()
        res = null
    }

    companion object {
        const val TAG = "JungleChess"
    }
}
