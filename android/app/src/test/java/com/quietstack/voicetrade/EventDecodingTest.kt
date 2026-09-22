package com.quietstack.voicetrade

import com.quietstack.voicetrade.data.remote.AppJson
import com.quietstack.voicetrade.data.remote.dto.WsEnvelope
import com.quietstack.voicetrade.data.remote.dto.QuoteDto
import com.quietstack.voicetrade.data.remote.ws.OkHttpSessionEventsSocket
import com.quietstack.voicetrade.domain.model.ActionCard
import com.quietstack.voicetrade.domain.model.AgentState
import com.quietstack.voicetrade.domain.model.PreviewState
import com.quietstack.voicetrade.domain.model.Role
import com.quietstack.voicetrade.domain.model.SessionEvent
import kotlinx.serialization.decodeFromString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal

class EventDecodingTest {
    private fun decode(json: String): SessionEvent? =
        OkHttpSessionEventsSocket.decode(AppJson.instance.decodeFromString<WsEnvelope>(json))

    @Test fun `transcript event`() {
        val e = decode("""{"type":"transcript","sessionId":"s1","seq":4,"data":{"role":"agent","text":"Hello","isFinal":false,"messageId":"m1"}}""")
        e as SessionEvent.Transcript
        assertEquals(Role.AGENT, e.role)
        assertEquals(false, e.isFinal)
        assertEquals("m1", e.messageId)
    }

    @Test fun `agent state event`() {
        val e = decode("""{"type":"agent_state","seq":1,"data":{"state":"awaiting_confirmation"}}""")
        assertEquals(SessionEvent.AgentStateChanged(AgentState.AWAITING_CONFIRMATION), e)
    }

    @Test fun `preview created keeps exact decimals from numbers and strings`() {
        val e = decode(
            """{"type":"preview_created","seq":2,"data":{"preview":{"previewId":"p1","instrument":{"conid":9,"symbol":"INFY","name":"Infosys","exchange":"NSE","currency":"INR"},
            "side":"BUY","quantity":10,"type":"MKT","estimatedValue":{"amount":15114.10,"currency":"INR"},"limitPrice":"1511.40","expiresAt":"2026-09-25T10:01:00Z"}}}""",
        )
        e as SessionEvent.PreviewCreated
        assertEquals(BigDecimal("15114.10"), e.preview.estimatedValue.amount)
        assertEquals(BigDecimal("1511.40"), e.preview.limitPrice)
        assertEquals(10, e.preview.quantity)
    }

    @Test fun `quote card decodes via the kind discriminator`() {
        val e = decode(
            """{"type":"card","seq":3,"data":{"messageId":"c1","card":{"kind":"quote","quote":{"instrument":{"conid":1,"symbol":"RELIANCE","name":"Reliance","exchange":"NSE","currency":"INR"},
            "last":2948,"change":32.4,"changePct":1.1,"asOf":"2026-09-25T10:00:00Z"}}}}""",
        )
        e as SessionEvent.CardShown
        assertTrue(e.card is ActionCard.QuoteCard)
    }

    @Test fun `preview closed maps reasons`() {
        val e = decode("""{"type":"preview_closed","seq":5,"data":{"previewId":"p1","reason":"replaced"}}""")
        assertEquals(SessionEvent.PreviewClosed("p1", PreviewState.REPLACED), e)
    }

    @Test fun `unknown event types and malformed payloads are skipped not fatal`() {
        assertNull(decode("""{"type":"something_new","seq":1,"data":{"x":1}}"""))
        assertNull(decode("""{"type":"transcript","seq":1,"data":{"nope":true}}"""))
    }

    @Test fun `extra fields from a newer backend are ignored`() {
        val dto = AppJson.instance.decodeFromString<QuoteDto>(
            """{"instrument":{"conid":1,"symbol":"A","name":"A","exchange":"NSE","currency":"INR","futureField":1},"last":"10.5","change":0,"changePct":0,"asOf":"2026-09-25T10:00:00Z","brandNew":"x"}""",
        )
        assertEquals(BigDecimal("10.5"), dto.last)
    }

}
