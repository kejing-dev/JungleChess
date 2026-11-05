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
    @Volatile var volume: Int = 1

    private val pool: SoundPool = SoundPool.Builder()
        .setMaxStreams(4)
        .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
        .build()
    private val ids = HashMap<String, Int>()
    private val loaded = HashSet<Int>()
    private val random = Random(System.nanoTime())
    private var lastPlace = -1

    init {
        pool.setOnLoadCompleteListener { _, id, status -> if (status == 0) synchronized(loaded) { loaded += id } }
        val names = runCatching { context.assets.list("sounds")?.toList() }.getOrNull().orEmpty()
        for (file in names) runCatching {
            context.assets.openFd("sounds/$file").use { fd -> ids[file.removeSuffix(".wav")] = pool.load(fd, 1) }
        }.onFailure { Log.w(TAG, "sound $file failed: $it") }
        Log.i(TAG, "SOUNDS loaded ${ids.size}")
    }

    fun play(cue: Cue, animal: Animal?) {
        if (!enabled) return
        val name = when (cue) {
            Cue.SELECT -> "select"
            Cue.PLACE -> {
                var v = random.nextInt(3)
                if (v == lastPlace) v = (v + 1) % 3
                lastPlace = v
                if (v == 0) "place" else "place_${v + 1}"
            }
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
        val id = ids[name] ?: return
        if (synchronized(loaded) { id !in loaded }) return
        val base = when (volume) { 0 -> 0.35f; 1 -> 0.65f; else -> 1f }
        val rate = if (cue == Cue.PLACE || cue == Cue.SELECT) 0.93f + random.nextFloat() * 0.14f else 1f
        pool.play(id, base, base, 1, 0, rate)
    }

    fun release() = pool.release()

    companion object { const val TAG = "JungleChess" }
}
