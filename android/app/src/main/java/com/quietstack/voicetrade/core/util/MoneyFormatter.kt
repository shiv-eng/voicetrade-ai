package com.quietstack.voicetrade.core.util

import com.quietstack.voicetrade.domain.model.Money
import java.math.BigDecimal
import java.math.RoundingMode

/** Display formatting. INR uses lakh/crore grouping (12,34,567.00); everything else uses 3-digit grouping. */
object MoneyFormatter {

    fun symbol(currency: String): String = when (currency.uppercase()) {
        "INR" -> "₹"
        "USD" -> "$"
        "EUR" -> "€"
        "GBP" -> "£"
        else -> "$currency "
    }

    fun format(money: Money, decimals: Int = 2): String = format(money.amount, money.currency, decimals)

    fun format(amount: BigDecimal, currency: String, decimals: Int = 2): String {
        val sign = if (amount.signum() < 0) "-" else ""
        return sign + symbol(currency) + number(amount.abs(), currency, decimals)
    }

    /** Signed value with an explicit +/- so profit and loss never rely on colour alone. */
    fun formatSigned(amount: BigDecimal, currency: String, decimals: Int = 2): String {
        val sign = when {
            amount.signum() > 0 -> "+"
            amount.signum() < 0 -> "-"
            else -> ""
        }
        return sign + symbol(currency) + number(amount.abs(), currency, decimals)
    }

    fun formatPercent(pct: BigDecimal): String {
        val sign = if (pct.signum() > 0) "+" else if (pct.signum() < 0) "-" else ""
        return sign + pct.abs().setScale(2, RoundingMode.HALF_UP).toPlainString() + "%"
    }

    fun number(amount: BigDecimal, currency: String, decimals: Int = 2): String {
        val scaled = amount.abs().setScale(decimals, RoundingMode.HALF_UP)
        val plain = scaled.toPlainString()
        val intPart = plain.substringBefore('.')
        val frac = if (decimals > 0) "." + plain.substringAfter('.') else ""
        val grouped = if (currency.equals("INR", ignoreCase = true)) groupIndian(intPart) else groupWestern(intPart)
        return grouped + frac
    }

    private fun groupWestern(digits: String): String =
        digits.reversed().chunked(3).joinToString(",").reversed()

    private fun groupIndian(digits: String): String {
        if (digits.length <= 3) return digits
        val last3 = digits.takeLast(3)
        val rest = digits.dropLast(3)
        return rest.reversed().chunked(2).joinToString(",").reversed() + "," + last3
    }
}
