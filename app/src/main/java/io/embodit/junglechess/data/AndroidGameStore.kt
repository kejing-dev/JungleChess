package io.embodit.junglechess.data

import android.content.Context
import io.embodit.junglechess.domain.game.GameStore
import io.embodit.junglechess.domain.game.Language
import io.embodit.junglechess.domain.game.Settings
import java.io.File

/**
 * The game is written after every committed move to files/game.json (temp file -> verify -> atomic rename,
 * previous copy kept as .bak); settings live in SharedPreferences. Everything stays on the device.
 */
class AndroidGameStore(context: Context) : GameStore {
    private val dir = context.filesDir
    private val file = File(dir, "game.json")
    private val backup = File(dir, "game.json.bak")
    private val prefs = context.getSharedPreferences("jungle_settings", Context.MODE_PRIVATE)

    override fun loadGame(): String? =
        runCatching { file.takeIf { it.exists() }?.readText() }.getOrNull()
            ?: runCatching { backup.takeIf { it.exists() }?.readText() }.getOrNull()

    @Synchronized
    override fun saveGame(text: String?) {
        if (text == null) { file.delete(); return }
        val tmp = File(dir, "game.json.tmp")
        tmp.writeText(text)
        check(tmp.readText() == text) { "save verify failed" }
        if (file.exists()) file.copyTo(backup, overwrite = true)
        if (!tmp.renameTo(file)) { file.delete(); check(tmp.renameTo(file)) { "rename failed" } }
    }

    override fun loadSettings(): Settings = Settings(
        language = runCatching { Language.valueOf(prefs.getString("language", Language.SYSTEM.name)!!) }.getOrDefault(Language.SYSTEM),
        sound = prefs.getBoolean("sound", true),
        volume = prefs.getInt("volume", 1),
        reducedMotion = prefs.getBoolean("reducedMotion", false),
        litAlways = prefs.getBoolean("litAlways", false),
        firstRunDone = prefs.getBoolean("firstRunDone", false),
        gamesStarted = prefs.getInt("gamesStarted", 0),
    )

    override fun saveSettings(settings: Settings) {
        prefs.edit()
            .putString("language", settings.language.name)
            .putBoolean("sound", settings.sound)
            .putInt("volume", settings.volume)
            .putBoolean("reducedMotion", settings.reducedMotion)
            .putBoolean("litAlways", settings.litAlways)
            .putBoolean("firstRunDone", settings.firstRunDone)
            .putInt("gamesStarted", settings.gamesStarted)
            .apply()
    }
}
