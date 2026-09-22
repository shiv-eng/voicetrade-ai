package com.quietstack.voicetrade.domain.model

import java.time.Instant

enum class AgentState { IDLE, LISTENING, THINKING, SPEAKING, AWAITING_CONFIRMATION }

enum class Role { USER, AGENT, SYSTEM }

sealed interface ActionCard {
    data class QuoteCard(val quote: Quote) : ActionCard
    data class PositionsCard(val positions: List<Position>, val totals: List<Money>) : ActionCard
    data class AccountCard(val summary: AccountSummary) : ActionCard
    data class PreviewCard(val preview: OrderPreview, val state: PreviewState = PreviewState.ACTIVE) : ActionCard
    data class StatusCard(val order: Order) : ActionCard
    data class Disambiguation(val candidates: List<Instrument>) : ActionCard
    data class ErrorCard(val message: String, val retryable: Boolean) : ActionCard
    data class ChartCard(val chart: ChartData) : ActionCard
    data class OverviewCard(val overview: CompanyOverview) : ActionCard
    data class IposCard(val ipos: IpoList) : ActionCard
    data class IpoDetailCard(val ipoDetail: IpoDetail) : ActionCard
}

data class ConversationMessage(
    val id: String,
    val role: Role,
    val text: String?,
    val card: ActionCard?,
    val isFinal: Boolean,
    val timestamp: Instant,
)

fun List<ConversationMessage>.latestActivePreview(): OrderPreview? =
    asReversed().firstNotNullOfOrNull { msg ->
        (msg.card as? ActionCard.PreviewCard)?.takeIf { it.state == PreviewState.ACTIVE }?.preview
    }
