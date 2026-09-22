package com.quietstack.voicetrade.data.local.prefs

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.quietstack.voicetrade.domain.model.ConnectionConfig
import com.quietstack.voicetrade.domain.model.UserProfile
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The signed-in user's device token, profile and the active session id, encrypted at rest.
 * Values are cached in memory so the OkHttp interceptors can read them without suspending.
 */
@Singleton
class SecureTokenStore @Inject constructor(@ApplicationContext private val context: Context) {

    private val prefs: SharedPreferences by lazy { openPrefs() }

    private val _connection = MutableStateFlow(load())
    val connection: StateFlow<ConnectionConfig> = _connection.asStateFlow()

    @Volatile
    var accessToken: String? = runCatching { prefs.getString(KEY_TOKEN, null) }.getOrNull()
        private set

    var activeSessionId: String?
        get() = runCatching { prefs.getString(KEY_SESSION, null) }.getOrNull()
        set(value) {
            runCatching { prefs.edit().apply { if (value == null) remove(KEY_SESSION) else putString(KEY_SESSION, value) }.apply() }
        }

    fun signIn(token: String, profile: UserProfile) {
        accessToken = token
        runCatching {
            prefs.edit()
                .putString(KEY_TOKEN, token)
                .putString(KEY_USER_ID, profile.userId)
                .putString(KEY_EMAIL, profile.email)
                .putString(KEY_NAME, profile.name)
                .putString(KEY_PICTURE, profile.picture)
                .apply()
        }.onFailure { Timber.w(it, "Could not persist sign-in") }
        _connection.value = ConnectionConfig(isLinked = true, profile = profile)
    }

    fun clear() {
        accessToken = null
        runCatching { prefs.edit().clear().apply() }
        _connection.value = ConnectionConfig()
    }

    private fun load(): ConnectionConfig = runCatching {
        val token = prefs.getString(KEY_TOKEN, null)
        val id = prefs.getString(KEY_USER_ID, null)
        if (token == null || id == null) {
            ConnectionConfig()
        } else {
            ConnectionConfig(
                isLinked = true,
                profile = UserProfile(id, prefs.getString(KEY_EMAIL, null), prefs.getString(KEY_NAME, "").orEmpty(), prefs.getString(KEY_PICTURE, null)),
            )
        }
    }.getOrDefault(ConnectionConfig())

    private fun openPrefs(): SharedPreferences {
        fun create(): SharedPreferences {
            val key = MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
            return EncryptedSharedPreferences.create(
                context,
                FILE,
                key,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
        }
        return try {
            create()
        } catch (e: Exception) {
            // Keystore invalidated (restore, lock-screen change): start clean rather than crash.
            Timber.w(e, "Secure prefs unreadable; resetting")
            context.deleteSharedPreferences(FILE)
            create()
        }
    }

    private companion object {
        const val FILE = "voicetrade_secure"
        const val KEY_TOKEN = "access_token"
        const val KEY_USER_ID = "user_id"
        const val KEY_EMAIL = "email"
        const val KEY_NAME = "name"
        const val KEY_PICTURE = "picture"
        const val KEY_SESSION = "active_session"
    }
}
