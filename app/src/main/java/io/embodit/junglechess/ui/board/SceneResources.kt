package io.embodit.junglechess.ui.board

import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Log
import com.pico.spatial.core.ecs.resource.BlendingMode
import com.pico.spatial.core.ecs.resource.Material
import com.pico.spatial.core.ecs.resource.MaterialCullingMode
import com.pico.spatial.core.ecs.resource.MeshResource
import com.pico.spatial.core.ecs.resource.PhysicallyBasedMaterial
import com.pico.spatial.core.ecs.resource.PhysicsMaterialResource
import com.pico.spatial.core.ecs.resource.Resource
import com.pico.spatial.core.ecs.resource.ShapeResource
import com.pico.spatial.core.ecs.resource.TextureResource
import com.pico.spatial.core.ecs.resource.UnlitMaterial
import com.pico.spatial.core.math.Color4
import com.pico.spatial.core.math.Vector3
import io.embodit.junglechess.domain.model.Animal
import io.embodit.junglechess.domain.model.Pieces
import io.embodit.junglechess.domain.model.Side
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

private fun rgb(c: Long, a: Float = 1f): Color4 = Color4(((c shr 16) and 0xFF) / 255f, ((c shr 8) and 0xFF) / 255f, (c and 0xFF) / 255f, a)

/**
 * Meshes, materials, textures and collision shapes of the board scene. Every SDK resource call blocks on the
 * renderer (~1 s on the emulator), so [create] issues them concurrently on Dispatchers.IO; entities are made on
 * the main thread. Resources are toGlobal and released in [close].
 */
internal class SceneResources private constructor() {
    private val all = ArrayList<Resource>()
    private fun <T : Resource> T.keep(): T { toGlobal(); all += this; return this }

    lateinit var physics: PhysicsMaterialResource
    lateinit var padShape: ShapeResource
    val pieceShape = HashMap<Animal, ShapeResource>()

    lateinit var markDot: MeshResource
    lateinit var markCell: MeshResource
    lateinit var markRing: MeshResource
    lateinit var markCross: MeshResource
    lateinit var denPlateMesh: MeshResource
    lateinit var filesMesh: MeshResource
    lateinit var ranksMesh: MeshResource

    lateinit var legalDot: Material
    lateinit var captureFrame: Material
    lateinit var jumpFrame: Material
    lateinit var badFrame: Material
    lateinit var hintRing: Material
    lateinit var hintFrame: Material
    lateinit var lastFrame: Material
    lateinit var alertFrame: Material
    lateinit var alertRing: Material
    lateinit var litRing: Material
    lateinit var selRing: Material
    lateinit var crossMat: Material
    lateinit var ripple: UnlitMaterial
    lateinit var filesMat: Material
    lateinit var ranksMat: Material
    lateinit var testMat: Material

    val teamBody = HashMap<Side, Material>()
    lateinit var ringGold: Material
    lateinit var ringGrey: Material
    /** Base-top label per piece id, current language. */
    var labels: Map<Int, Material> = emptyMap()
    var denPlate: Material? = null
    var labelsZh: Boolean? = null

    fun close() {
        all.forEach { runCatching { it.close() } }
        all.clear()
    }

    companion object {
        private const val TAG = "JungleChess"

        private fun unlit(tex: TextureResource): UnlitMaterial = UnlitMaterial.create(BlendingMode.TRANSPARENT).apply {
            setBaseColor(Color4(1f, 1f, 1f, 1f))
            setBaseColorTexture(tex)
            setCullingMode(MaterialCullingMode.NONE)
            setApplyToneMapping(false)
        }

        private fun pbr(color: Color4, roughness: Float, glow: Float = 0.08f): PhysicallyBasedMaterial =
            PhysicallyBasedMaterial.create(BlendingMode.OPAQUE).apply {
                setBaseColor(color)
                setEmissiveColor(Color4(color.red * glow, color.green * glow, color.blue * glow, 1f))
                setRoughness(roughness)
                setMetallic(0f)
            }

        suspend fun create(zh: Boolean): SceneResources = coroutineScope {
            val t0 = SystemClock.elapsedRealtime()
            val r = SceneResources()
            fun tex(b: () -> Bitmap) = async(Dispatchers.IO) { TextureResource.create(b()) }
            val texJobs = linkedMapOf(
                "dot" to tex { BoardTextures.disc(0xE63D8BE0.toInt()) },
                "cap" to tex { BoardTextures.frame(0xF2F08A24.toInt(), dot = true) },
                "jump" to tex { BoardTextures.frame(0xF23D8BE0.toInt(), arc = true) },
                "bad" to tex { BoardTextures.frame(0xFFE0453A.toInt()) },
                "hintRing" to tex { BoardTextures.ring(0xFFF2C12E.toInt(), 0.12f) },
                "hintFrame" to tex { BoardTextures.frame(0xFFF2C12E.toInt()) },
                "last" to tex { BoardTextures.frame(0xD9FFFFFF.toInt(), dashed = true) },
                "alertFrame" to tex { BoardTextures.frame(0xFFE0453A.toInt(), dot = false) },
                "alertRing" to tex { BoardTextures.ring(0xFFE0453A.toInt(), 0.12f) },
                "lit" to tex { BoardTextures.ring(0xCCEFFFD8.toInt(), 0.09f) },
                "sel" to tex { BoardTextures.ring(0xF2FFFFFF.toInt(), 0.1f) },
                "cross" to tex { BoardTextures.cross(0xFFE0453A.toInt()) },
                "ripple" to tex { BoardTextures.ring(0xCCFFFFFF.toInt(), 0.06f) },
                "files" to tex { BoardTextures.files() },
                "ranks" to tex { BoardTextures.ranks() },
                "test" to tex { BoardTextures.testArrow() },
            )
            val meshJobs = listOf(
                async(Dispatchers.IO) { MeshResource.createPlane(0.018f, 0.018f, 0f) },
                async(Dispatchers.IO) { MeshResource.createPlane(0.058f, 0.058f, 0f) },
                async(Dispatchers.IO) { MeshResource.createPlane(0.060f, 0.060f, 0f) },
                async(Dispatchers.IO) { MeshResource.createPlane(0.016f, 0.016f, 0f) },
                async(Dispatchers.IO) { MeshResource.createPlane(0.034f, 0.0125f, 0f) },
                async(Dispatchers.IO) { MeshResource.createPlane(0.42f, 0.03f, 0f) },
                async(Dispatchers.IO) { MeshResource.createPlane(0.03f, 0.54f, 0f) },
            )
            val shapeJob = async(Dispatchers.IO) {
                val pad = ShapeResource.createBox(Vector3(0.056f, 0.004f, 0.056f))
                val pieces = Animal.entries.associateWith { a ->
                    ShapeResource.createBox(Vector3(0.046f, BoardLayout.BASE_H + BoardLayout.animalHeight(a) + 0.004f, 0.046f))
                }
                pad to pieces
            }
            val labelJob = async(Dispatchers.IO) { labelMaterials(zh) }
            val tx = texJobs.mapValues { it.value.await() }
            val m = meshJobs.awaitAll()
            val (pad, shapes) = shapeJob.await()
            with(r) {
            r.markDot = m[0].keep(); r.markCell = m[1].keep(); r.markRing = m[2].keep(); r.markCross = m[3].keep()
            r.denPlateMesh = m[4].keep(); r.filesMesh = m[5].keep(); r.ranksMesh = m[6].keep()
            r.padShape = pad.keep()
            shapes.forEach { (a, s) -> r.pieceShape[a] = s.keep() }
            r.physics = PhysicsMaterialResource().keep()
            tx.values.forEach { it.keep() }
            r.legalDot = unlit(tx.getValue("dot")).keep()
            r.captureFrame = unlit(tx.getValue("cap")).keep()
            r.jumpFrame = unlit(tx.getValue("jump")).keep()
            r.badFrame = unlit(tx.getValue("bad")).keep()
            r.hintRing = unlit(tx.getValue("hintRing")).keep()
            r.hintFrame = unlit(tx.getValue("hintFrame")).keep()
            r.lastFrame = unlit(tx.getValue("last")).keep()
            r.alertFrame = unlit(tx.getValue("alertFrame")).keep()
            r.alertRing = unlit(tx.getValue("alertRing")).keep()
            r.litRing = unlit(tx.getValue("lit")).keep()
            r.selRing = unlit(tx.getValue("sel")).keep()
            r.crossMat = unlit(tx.getValue("cross")).keep()
            r.ripple = unlit(tx.getValue("ripple")).keep()
            r.filesMat = unlit(tx.getValue("files")).keep()
            r.ranksMat = unlit(tx.getValue("ranks")).keep()
            r.testMat = unlit(tx.getValue("test")).keep()
            r.teamBody[Side.RED] = pbr(rgb(0xB5452E), 0.55f).keep()
            r.teamBody[Side.BLUE] = pbr(rgb(0x2F5F9E), 0.55f).keep()
            r.ringGold = pbr(rgb(0xD9B45A), 0.45f, glow = 0.15f).keep()
            r.ringGrey = pbr(rgb(0x6E6E6A), 0.8f, glow = 0.02f).keep()
            val (labels, plate) = labelJob.await()
            r.labels = labels.onEach { it.value.keep() }
            r.denPlate = plate.keep()
            r.labelsZh = zh
            }
            Log.i(TAG, "SCENE resources ${SystemClock.elapsedRealtime() - t0} ms")
            r
        }

        /** Base-top labels and den plate for one language (rebuilt on a language switch). */
        suspend fun labelMaterials(zh: Boolean): Pair<Map<Int, Material>, Material> = coroutineScope {
            val jobs = (0 until Pieces.COUNT).map { id -> id to async(Dispatchers.IO) { TextureResource.create(BoardTextures.baseTop(id, zh)) } }
            val plateTex = async(Dispatchers.IO) { TextureResource.create(BoardTextures.denPlate(zh)) }
            val labels = jobs.associate { (id, d) ->
                val tex = d.await().also { it.toGlobal() }
                id to (PhysicallyBasedMaterial.create(BlendingMode.OPAQUE).apply {
                    setBaseColor(Color4(1f, 1f, 1f, 1f)); setRoughness(0.6f); setMetallic(0f); setBaseColorTexture(tex)
                    setEmissiveColor(Color4(0.06f, 0.06f, 0.06f, 1f))
                } as Material)
            }
            val pt = plateTex.await().also { it.toGlobal() }
            val plate = PhysicallyBasedMaterial.create(BlendingMode.OPAQUE).apply {
                setBaseColor(Color4(1f, 1f, 1f, 1f)); setRoughness(0.8f); setMetallic(0f); setBaseColorTexture(pt)
                setEmissiveColor(Color4(0.1f, 0.1f, 0.1f, 1f))
            }
            labels to plate
        }
    }
}
