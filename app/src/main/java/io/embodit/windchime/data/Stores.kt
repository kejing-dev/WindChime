package io.embodit.windchime.data

import android.content.Context
import io.embodit.windchime.domain.ChimeKind
import io.embodit.windchime.domain.Language
import io.embodit.windchime.domain.Settings
import io.embodit.windchime.domain.WindLevel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** App settings in SharedPreferences (design offline.save: no progress to keep), as a StateFlow for every window. */
class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val _settings = MutableStateFlow(read())
    val settings: StateFlow<Settings> = _settings.asStateFlow()
    val value: Settings get() = _settings.value

    fun update(change: (Settings) -> Settings) = set(change(_settings.value))

    fun set(s: Settings) {
        _settings.value = s
        prefs.edit()
            .putString("chime", s.chime.id)
            .putInt("wind", s.wind.index)
            .putFloat("volume", s.volume)
            .putBoolean("muted", s.muted)
            .putBoolean("gustSound", s.gustSound)
            .putBoolean("reducedMotion", s.reducedMotion)
            .putString("language", s.language.name)
            .putBoolean("firstRunDone", s.firstRunDone)
            .apply()
    }

    /** Debug `fresh`: back to the first-run defaults. */
    fun clear() { prefs.edit().clear().apply(); _settings.value = Settings() }

    private fun read(): Settings = runCatching {
        Settings(
            chime = ChimeKind.of(prefs.getString("chime", null)),
            wind = WindLevel.of(prefs.getInt("wind", 0)),
            volume = prefs.getFloat("volume", 1f).coerceIn(0f, 1f),
            muted = prefs.getBoolean("muted", false),
            gustSound = prefs.getBoolean("gustSound", true),
            reducedMotion = prefs.getBoolean("reducedMotion", false),
            language = Language.valueOf(prefs.getString("language", Language.SYSTEM.name)!!),
            firstRunDone = prefs.getBoolean("firstRunDone", false),
        )
    }.getOrElse { Settings() }
}
