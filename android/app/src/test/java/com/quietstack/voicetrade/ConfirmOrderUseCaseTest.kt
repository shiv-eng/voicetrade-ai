package com.quietstack.voicetrade

import com.quietstack.voicetrade.core.common.AppError
import com.quietstack.voicetrade.domain.model.ActionCard
import com.quietstack.voicetrade.domain.model.ConfirmResult
import com.quietstack.voicetrade.domain.model.ConversationMessage
import com.quietstack.voicetrade.domain.model.Instrument
import com.quietstack.voicetrade.domain.model.Money
import com.quietstack.voicetrade.domain.model.OrderPreview
import com.quietstack.voicetrade.domain.model.OrderStatus
import com.quietstack.voicetrade.domain.model.OrderType
import com.quietstack.voicetrade.domain.model.PreviewKind
import com.quietstack.voicetrade.domain.model.PreviewState
import com.quietstack.voicetrade.domain.model.Role
import com.quietstack.voicetrade.domain.model.Side
import com.quietstack.voicetrade.domain.repository.OrderRepository
import com.quietstack.voicetrade.domain.repository.VoiceSessionRepository
import com.quietstack.voicetrade.domain.usecase.ConfirmOrderUseCase
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/** The client-side half of the order safety gate (PRD 4.3, 13.1, TC-01/TC-04/TC-05). */
class ConfirmOrderUseCaseTest {
    private val now = Instant.parse("2026-09-25T10:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)
    private val infy = Instrument(1, "INFY", "Infosys", "NSE", "INR")

    private lateinit var messages: MutableStateFlow<List<ConversationMessage>>
    private lateinit var session: VoiceSessionRepository
    private lateinit var orders: OrderRepository
    private lateinit var useCase: ConfirmOrderUseCase

    @Before fun setUp() {
        messages = MutableStateFlow(emptyList())
        session = mockk(relaxed = true) { every { this@mockk.messages } returns this@ConfirmOrderUseCaseTest.messages }
        orders = mockk()
        useCase = ConfirmOrderUseCase(session, orders, clock)
    }

    private fun preview(id: String, expiresInSeconds: Long, kind: PreviewKind = PreviewKind.PLACE, state: PreviewState = PreviewState.ACTIVE) =
        ConversationMessage(
            id = "preview-$id",
            role = Role.AGENT,
            text = null,
            card = ActionCard.PreviewCard(
                OrderPreview(
                    previewId = id,
                    instrument = infy,
                    side = Side.BUY,
                    quantity = 10,
                    type = OrderType.MARKET,
                    limitPrice = null,
                    estimatedValue = Money(BigDecimal("15114"), "INR"),
                    estimatedFees = null,
                    warnings = emptyList(),
                    expiresAt = now.plusSeconds(expiresInSeconds),
                    kind = kind,
                ),
                state,
            ),
            isFinal = true,
            timestamp = now,
        )

    @Test fun `nothing is sent when there is no preview`() = runTest {
        val result = useCase("p1")
        assertTrue(result.exceptionOrNull() is AppError.PreviewExpired)
        coVerify(exactly = 0) { orders.confirm(any()) }
    }

    @Test fun `an expired preview is never sent and is marked expired`() = runTest {
        messages.value = listOf(preview("p1", expiresInSeconds = -1))
        val result = useCase("p1")
        assertTrue(result.exceptionOrNull() is AppError.PreviewExpired)
        verify { session.markPreview("p1", PreviewState.EXPIRED) }
        coVerify(exactly = 0) { orders.confirm(any()) }
    }

    @Test fun `only the latest preview can be confirmed`() = runTest {
        messages.value = listOf(preview("old", 30, state = PreviewState.REPLACED), preview("new", 30))
        val result = useCase("old")
        assertTrue(result.exceptionOrNull() is AppError.PreviewExpired)
        coVerify(exactly = 0) { orders.confirm(any()) }
    }

    @Test fun `a live preview is confirmed once and shows a working order`() = runTest {
        messages.value = listOf(preview("p1", 30))
        coEvery { orders.confirm("p1") } returns Result.success(ConfirmResult("777", "Working"))
        val order = slot<com.quietstack.voicetrade.domain.model.Order>()
        every { session.upsertOrder(capture(order)) } just runs

        val result = useCase("p1")

        assertTrue(result.isSuccess)
        coVerify(exactly = 1) { orders.confirm("p1") }
        verify { session.markPreview("p1", PreviewState.CONFIRMED) }
        assertEquals("777", order.captured.orderId)
        assertEquals(OrderStatus.Working, order.captured.status)
        assertEquals(10, order.captured.quantity)
    }

    @Test fun `a cancel preview does not invent a new order`() = runTest {
        messages.value = listOf(preview("c1", 30, kind = PreviewKind.CANCEL))
        coEvery { orders.confirm("c1") } returns Result.success(ConfirmResult("777", "Cancelled"))
        useCase("c1")
        verify(exactly = 0) { session.upsertOrder(any()) }
    }

    @Test fun `a price drift rejection retires the preview so it cannot be retried`() = runTest {
        messages.value = listOf(preview("p1", 30))
        coEvery { orders.confirm("p1") } returns Result.failure(AppError.PriceDrift())
        val result = useCase("p1")
        assertTrue(result.exceptionOrNull() is AppError.PriceDrift)
        verify { session.markPreview("p1", PreviewState.EXPIRED) }
        verify(exactly = 0) { session.upsertOrder(any()) }
    }
}
