package io.embodit.junglechess.platform

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import android.util.Log
import io.embodit.junglechess.domain.game.Cue
import io.embodit.junglechess.domain.model.Animal
import kotlin.random.Random

/**
 * Event sounds from assets/sounds (tools/asset-pipeline/sounds.mjs), no ambience (B24). Repeated sounds get
 * ±7 % rate and rotate through their variants without repeating back to back. [enabled] mutes everything;
 * [volume] 0 / 1 / 2 is the three-step slider.
 */
class SoundPlayer(context: Context) {
    @Volatile var enabled: Boolean = true
    @Volatile var volume: Int = 2

    private val pool: SoundPool = SoundPool.Builder()
        .setMaxStreams(4)
        .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
        .build()
    private val ids = HashMap<String, Int>()
    private val loaded = HashSet<Int>()
    private val random = Random(System.nanoTime())

    init {
        pool.setOnLoadCompleteListener { _, id, status -> if (status == 0) synchronized(loaded) { loaded += id } }
        val names = runCatching { context.assets.list("sounds")?.toList() }.getOrNull().orEmpty()
        for (file in names) runCatching {
            context.assets.openFd("sounds/$file").use { fd -> ids[file.removeSuffix(".wav")] = pool.load(fd, 1) }
        }.onFailure { Log.w(TAG, "sound $file failed: $it") }
        Log.i(TAG, "SOUNDS loaded ${ids.size}")
    }

    private val lastVariant = HashMap<String, Int>()

    /** Every event plays one of its 1–4 variants (`name`, `name_2` …) without repeating back to back, ±5 % pitch. */
    fun play(cue: Cue, animal: Animal?) {
        if (!enabled) return
        val base = when (cue) {
            Cue.SELECT -> return // D51: select / preselect / hover are silent by default
            Cue.PLACE -> "place"
            Cue.CAPTURE -> "cry_${(animal ?: Animal.RAT).asset}"
            Cue.TOPPLE -> "topple"
            Cue.JUMP -> "jump"
            Cue.SPLASH -> "splash"
            Cue.DRIP -> "drip"
            Cue.TRAP -> "trap"
            Cue.ILLEGAL -> "illegal"
            Cue.ALARM -> "alarm"
            Cue.WIN -> "win"
            Cue.LOSE -> "lose"
        }
        val variants = listOf(base, "${base}_2", "${base}_3", "${base}_4").filter { it in ids }
        if (variants.isEmpty()) return
        var v = random.nextInt(variants.size)
        if (variants.size > 1 && v == lastVariant[base]) v = (v + 1) % variants.size
        lastVariant[base] = v
        val id = ids.getValue(variants[v])
        if (synchronized(loaded) { id !in loaded }) return
        val gain = when (volume) { 0 -> 0.35f; 1 -> 0.65f; else -> 1f }
        val rate = 0.95f + random.nextFloat() * 0.10f
        pool.play(id, gain, gain, 1, 0, rate)
    }

    fun release() = pool.release()

    companion object { const val TAG = "JungleChess" }
}
