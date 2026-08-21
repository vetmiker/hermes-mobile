package com.m57.hermescontrol.glasses.myvu

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Process-owned, persisted MYVU readability settings shared by UI and renderer. */
object GlassesReadabilityStore {
    private const val PREFERENCES = "myvu_readability"
    private const val FONT_MODE = "font_mode"
    private const val PACING_MILLIS = "pacing_millis"

    private val _readability = MutableStateFlow(GlassesReadability())
    val readability: StateFlow<GlassesReadability> = _readability.asStateFlow()

    @Volatile
    private var preferences: android.content.SharedPreferences? = null

    fun initialize(context: Context) {
        if (preferences != null) return
        synchronized(this) {
            if (preferences != null) return
            val loaded = context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            preferences = loaded
            _readability.value =
                GlassesReadability(
                    fontMode =
                        loaded.getString(FONT_MODE, GlassesFontMode.Standard.name)
                            ?.let { runCatching { GlassesFontMode.valueOf(it) }.getOrNull() }
                            ?: GlassesFontMode.Standard,
                    pacingMillis = loaded.getInt(PACING_MILLIS, GlassesReadability.DEFAULT_PACING_MILLIS),
                )
        }
    }

    fun setFontMode(fontMode: GlassesFontMode) = update(_readability.value.copy(fontMode = fontMode))

    fun setPacingMillis(pacingMillis: Int) {
        if (pacingMillis > 0) update(_readability.value.copy(pacingMillis = pacingMillis))
    }

    private fun update(value: GlassesReadability) {
        _readability.value = value
        preferences
            ?.edit()
            ?.putString(FONT_MODE, value.fontMode.name)
            ?.putInt(PACING_MILLIS, value.pacingMillis)
            ?.apply()
    }
}
