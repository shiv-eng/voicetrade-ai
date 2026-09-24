package com.quietstack.voicetrade.data.local.prefs

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.quietstack.voicetrade.domain.model.AppSettings
import com.quietstack.voicetrade.domain.model.Language
import com.quietstack.voicetrade.domain.model.ThemeMode
import com.quietstack.voicetrade.domain.model.VoiceGender
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore by preferencesDataStore(name = "voicetrade_settings")

@Singleton
class SettingsDataStore @Inject constructor(@ApplicationContext private val context: Context) {

    val settings: Flow<AppSettings> = context.dataStore.data.map { it.toSettings() }

    suspend fun update(transform: (AppSettings) -> AppSettings) {
        context.dataStore.edit { prefs ->
            val next = transform(prefs.toSettings())
            prefs[LANGUAGE] = next.language.name
            prefs[VOICE] = next.voice.name
            prefs[RATE] = next.speechRate
            prefs[THEME] = next.theme.name
            prefs[ONBOARDED] = next.onboardingDone
        }
    }

    private fun Preferences.toSettings() = AppSettings(
        language = enumOr(this[LANGUAGE], Language.HINGLISH),
        voice = enumOr(this[VOICE], VoiceGender.FEMALE),
        speechRate = this[RATE] ?: 1.0f,
        theme = enumOr(this[THEME], ThemeMode.LIGHT),
        onboardingDone = this[ONBOARDED] ?: false,
    )

    private inline fun <reified E : Enum<E>> enumOr(raw: String?, default: E): E =
        raw?.let { runCatching { enumValueOf<E>(it) }.getOrNull() } ?: default

    private companion object {
        val LANGUAGE = stringPreferencesKey("language")
        val VOICE = stringPreferencesKey("voice")
        val RATE = floatPreferencesKey("speech_rate")
        val THEME = stringPreferencesKey("theme_v2")
        val ONBOARDED = booleanPreferencesKey("onboarded")
    }
}
