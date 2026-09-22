package com.quietstack.voicetrade.domain.model

import java.math.BigDecimal

enum class Language(val code: String) { ENGLISH("en"), HINGLISH("hinglish") }
enum class VoiceGender(val code: String) { FEMALE("female"), MALE("male") }
enum class ThemeMode { SYSTEM, LIGHT, DARK }

data class AppSettings(
    val language: Language = Language.HINGLISH,
    val voice: VoiceGender = VoiceGender.FEMALE,
    val speechRate: Float = 1.0f,
    val biometricOn: Boolean = false,
    val theme: ThemeMode = ThemeMode.SYSTEM,
    val largeText: Boolean = false,
    val onboardingDone: Boolean = false,
)

data class UserProfile(val userId: String, val email: String?, val name: String, val picture: String?)

/** Who is signed in on this phone (null profile = signed out). */
data class ConnectionConfig(
    val isLinked: Boolean = false,
    val profile: UserProfile? = null,
)

data class RiskLimits(
    val maxOrderValue: BigDecimal,
    val maxQuantity: Int,
    val maxOrdersPerDay: Int,
    val killSwitch: Boolean,
    val serverMaxOrderValue: BigDecimal? = null,
    val serverMaxQuantity: Int? = null,
    val serverMaxOrdersPerDay: Int? = null,
)

/** A watchlist entry with its live quote (null if the price feed had no answer). */
data class WatchRow(val instrument: Instrument, val quote: Quote?)
