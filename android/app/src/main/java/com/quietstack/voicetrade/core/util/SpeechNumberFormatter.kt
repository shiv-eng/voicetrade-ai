package com.quietstack.voicetrade.core.util

import com.quietstack.voicetrade.domain.model.Money
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Says numbers the way people say them, for TalkBack labels. Rupees use lakh / crore,
 * other currencies use thousand / million.
 */
object SpeechNumberFormatter {

    private val crore = BigDecimal("10000000")
    private val lakh = BigDecimal("100000")
    private val thousand = BigDecimal("1000")
    private val million = BigDecimal("1000000")

    fun spoken(money: Money): String = spoken(money.amount, money.currency)

    fun spoken(amount: BigDecimal, currency: String): String {
        val negative = amount.signum() < 0
        val abs = amount.abs()
        val unit = unitName(currency, abs)
        val body = if (currency.equals("INR", ignoreCase = true)) indian(abs) else western(abs)
        return (if (negative) "minus " else "") + "$body $unit"
    }

    fun spokenPercent(pct: BigDecimal): String {
        val direction = when {
            pct.signum() > 0 -> "up"
            pct.signum() < 0 -> "down"
            else -> "flat at"
        }
        val value = pct.abs().setScale(1, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()
        return if (pct.signum() == 0) "flat" else "$direction $value percent"
    }

    private fun indian(abs: BigDecimal): String = when {
        abs >= crore -> scaled(abs, crore, "crore")
        abs >= lakh -> scaled(abs, lakh, "lakh")
        else -> plain(abs)
    }

    private fun western(abs: BigDecimal): String = when {
        abs >= million -> scaled(abs, million, "million")
        abs >= thousand -> scaled(abs, thousand, "thousand")
        else -> plain(abs)
    }

    private fun scaled(abs: BigDecimal, divisor: BigDecimal, word: String): String {
        val value = abs.divide(divisor, 2, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()
        return "$value $word"
    }

    private fun plain(abs: BigDecimal): String =
        abs.setScale(2, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()

    private fun unitName(currency: String, abs: BigDecimal): String = when (currency.uppercase()) {
        "INR" -> "rupees"
        "USD" -> if (abs.compareTo(BigDecimal.ONE) == 0) "dollar" else "dollars"
        "EUR" -> "euros"
        "GBP" -> "pounds"
        else -> currency
    }
}
