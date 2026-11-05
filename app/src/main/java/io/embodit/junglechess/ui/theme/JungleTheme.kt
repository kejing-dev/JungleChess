package io.embodit.junglechess.ui.theme

import android.content.Context
import android.content.ContextWrapper
import android.content.res.Configuration
import android.content.res.Resources
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.design.systemColorScheme
import io.embodit.junglechess.domain.game.Language
import io.embodit.junglechess.ui.i18n.Texts
import java.util.Locale

val LocalTexts = staticCompositionLocalOf { Texts(zh = true) }

/**
 * PicoTheme with all 16 ColorScheme roles assigned (system Vibrant scheme + the design's semantic colours)
 * and Noto Sans. [language] localises resources and [LocalTexts] without restarting anything.
 */
@Composable
fun JungleTheme(language: Language = Language.SYSTEM, content: @Composable () -> Unit) {
    val base = LocalContext.current
    val localized = remember(base, language) { base.localized(language) }
    val texts = remember(language) { Texts.of(language) }
    val system = systemColorScheme(localized)
    val scheme = system.copy(
        fillPrimary = system.fillPrimary,
        fillSecondary = system.fillSecondary,
        fillTertiary = JungleColors.FillTertiary,
        fillLight = system.fillLight,
        labelPrimaryLight = JungleColors.OnInteraction,
        labelPrimary = system.labelPrimary,
        labelSecondary = system.labelSecondary,
        labelTertiary = system.labelTertiary,
        labelQuaternary = system.labelQuaternary,
        lightenHover = system.lightenHover,
        lightenPressed = system.lightenPressed,
        error = JungleColors.Error,
        alert = JungleColors.Alert,
        passable = JungleColors.Passable,
        interaction = JungleColors.Interaction,
        dividerLine = JungleColors.DividerLine,
    )
    CompositionLocalProvider(LocalContext provides localized, LocalTexts provides texts) {
        PicoTheme(colorScheme = scheme, typography = PicoTheme.typography.withNotoSans(), content = content)
    }
}

fun Context.localized(language: Language): Context {
    val locale = when (language) {
        Language.SYSTEM -> return this
        Language.ZH -> Locale.SIMPLIFIED_CHINESE
        Language.EN -> Locale.ENGLISH
    }
    val config = Configuration(resources.configuration)
    config.setLocale(locale)
    // A wrapper, not the bare configuration context: the Spatial SDK walks the context chain to find the
    // Activity that owns a SpatialView (Cloud Air AGENTS.md).
    val localizedResources = createConfigurationContext(config).resources
    return object : ContextWrapper(this) {
        override fun getResources(): Resources = localizedResources
    }
}
