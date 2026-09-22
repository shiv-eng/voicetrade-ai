package com.quietstack.voicetrade

import com.quietstack.voicetrade.core.util.BiometricPolicy
import com.quietstack.voicetrade.core.util.MoneyFormatter
import com.quietstack.voicetrade.core.util.SpeechNumberFormatter
import com.quietstack.voicetrade.domain.model.Money
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal

class FormatterTest {
    private fun bd(s: String) = BigDecimal(s)

    @Test fun `inr uses lakh and crore grouping`() {
        assertEquals("₹12,34,567.00", MoneyFormatter.format(bd("1234567"), "INR"))
        assertEquals("₹15,120.00", MoneyFormatter.format(bd("15120"), "INR"))
        assertEquals("₹6,50,00,000.00", MoneyFormatter.format(bd("65000000"), "INR"))
    }

    @Test fun `usd uses western grouping`() {
        assertEquals("$1,234,567.50", MoneyFormatter.format(bd("1234567.5"), "USD"))
    }

    @Test fun `negative amounts keep the sign in front of the symbol`() {
        assertEquals("-₹1,200.00", MoneyFormatter.format(bd("-1200"), "INR"))
    }

    @Test fun `signed formatting never relies on colour`() {
        assertEquals("+₹500", MoneyFormatter.formatSigned(bd("500"), "INR", 0))
        assertEquals("-₹500", MoneyFormatter.formatSigned(bd("-500"), "INR", 0))
        assertEquals("₹0", MoneyFormatter.formatSigned(bd("0"), "INR", 0))
        assertEquals("+1.10%", MoneyFormatter.formatPercent(bd("1.1")))
        assertEquals("-0.80%", MoneyFormatter.formatPercent(bd("-0.8")))
    }

    @Test fun `spoken rupees use lakh and crore`() {
        assertEquals("6.5 crore rupees", SpeechNumberFormatter.spoken(bd("65000000"), "INR"))
        assertEquals("10 lakh rupees", SpeechNumberFormatter.spoken(bd("1000000"), "INR"))
        assertEquals("1511.4 rupees", SpeechNumberFormatter.spoken(bd("1511.40"), "INR"))
    }

    @Test fun `spoken dollars use thousand and million`() {
        assertEquals("2.5 thousand dollars", SpeechNumberFormatter.spoken(bd("2500"), "USD"))
        assertEquals("minus 12 dollars", SpeechNumberFormatter.spoken(bd("-12"), "USD"))
    }

    @Test fun `spoken percent says up or down`() {
        assertEquals("up 1.2 percent", SpeechNumberFormatter.spokenPercent(bd("1.2")))
        assertEquals("down 0.8 percent", SpeechNumberFormatter.spokenPercent(bd("-0.8")))
        assertEquals("flat", SpeechNumberFormatter.spokenPercent(bd("0")))
    }

    @Test fun `biometric is required only above the threshold and only when enabled`() {
        assertTrue(BiometricPolicy.requiresAuth(Money(bd("25001"), "INR"), enabled = true))
        assertFalse(BiometricPolicy.requiresAuth(Money(bd("25000"), "INR"), enabled = true))
        assertFalse(BiometricPolicy.requiresAuth(Money(bd("900000"), "INR"), enabled = false))
        assertTrue(BiometricPolicy.requiresAuth(Money(bd("301"), "USD"), enabled = true))
    }
}
