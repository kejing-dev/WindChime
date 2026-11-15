package io.embodit.windchime.ui.theme

import android.content.Context
import android.content.ContextWrapper
import android.content.res.Configuration
import android.content.res.Resources
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.design.systemColorScheme
import io.embodit.windchime.domain.Language
import io.embodit.windchime.domain.Palette
import io.embodit.windchime.platform.Graph
import java.util.Locale

/** Sky blue of the icon, walnut, brass; text roles measured on the window glass (D13, tools/contrast.py). */
object ChimeColors {
    /** Interaction / selected segment / primary button: deep teal-blue, white text 7.6:1. */
    val Primary = Color(Palette.PRIMARY)
    val OnPrimary = Color(Palette.ON_PRIMARY)
    val PrimaryContainer = Color(Palette.PRIMARY_CONTAINER)
    val Secondary = Color(Palette.SECONDARY)
    val Error = Color(Palette.ERROR)
    val OnError = Color(Palette.ON_ERROR)
    val Ink = Color(Palette.INK)
    /** Secondary / small text on the window glass (>= 4.5:1 on the darkest measured glass #B4B3B1). */
    val InkSecondary = Color(Palette.INK_SECONDARY)
    val Amber = Color(Palette.AMBER)
    /** The sentence card. */
    val Card = Color(Palette.CARD)
    val Brass = Color(Palette.BRASS)
    val Walnut = Color(Palette.WALNUT)
    val Sky = Color(Palette.SKY)
}

/**
 * PicoTheme with all 16 roles assigned (Cloud Air pattern). The language setting localises resources below this
 * point through a ContextWrapper that only overrides getResources (a bare configuration context breaks SpatialView).
 */
@Composable
fun ChimeTheme(content: @Composable () -> Unit) {
    val base = LocalContext.current
    val graph = remember(base) { Graph.from(base) }
    val settings by graph.settings.settings.collectAsStateWithLifecycle()
    val localized = remember(base, settings.language) { base.localized(settings.language) }
    val system = systemColorScheme(localized)
    val scheme = system.copy(
        fillPrimary = system.fillPrimary,
        fillSecondary = system.fillSecondary,
        fillTertiary = system.fillTertiary,
        fillLight = system.fillLight,
        labelPrimaryLight = ChimeColors.OnPrimary,
        labelPrimary = ChimeColors.Ink,
        labelSecondary = ChimeColors.InkSecondary,
        labelTertiary = system.labelTertiary,
        labelQuaternary = system.labelQuaternary,
        lightenHover = system.lightenHover,
        lightenPressed = system.lightenPressed,
        error = ChimeColors.Error,
        alert = ChimeColors.Amber,
        passable = ChimeColors.Secondary,
        interaction = ChimeColors.Primary,
        dividerLine = system.dividerLine,
    )
    CompositionLocalProvider(LocalContext provides localized) {
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
    val res = createConfigurationContext(config).resources
    return object : ContextWrapper(this) {
        override fun getResources(): Resources = res
    }
}

/** Is the app language Chinese (settings, else the system locale)? */
fun isZh(language: Language): Boolean = when (language) {
    Language.ZH -> true
    Language.EN -> false
    Language.SYSTEM -> Locale.getDefault().language == "zh"
}
