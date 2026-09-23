package com.quietstack.voicetrade.core.util

import com.quietstack.voicetrade.domain.model.Money
import java.math.BigDecimal

/** Orders above roughly Rs 25,000 (or the USD equivalent) ask for a fingerprint or face before confirming.
 * A fixed rule, not a setting: there is nothing in Settings to turn it off. */
object BiometricPolicy {
    private val inrThreshold = BigDecimal("25000")
    private val otherThreshold = BigDecimal("300")

    fun requiresAuth(value: Money): Boolean {
        val limit = if (value.currency.equals("INR", ignoreCase = true)) inrThreshold else otherThreshold
        return value.amount > limit
    }
}
