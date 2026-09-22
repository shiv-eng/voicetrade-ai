package com.quietstack.voicetrade.data.repository

import com.quietstack.voicetrade.data.mapper.toDomain
import com.quietstack.voicetrade.data.remote.apiCall
import com.quietstack.voicetrade.domain.model.ChartData
import com.quietstack.voicetrade.domain.model.CompanyOverview
import com.quietstack.voicetrade.domain.model.IpoList
import com.quietstack.voicetrade.domain.repository.AlertsRepository
import com.quietstack.voicetrade.domain.repository.ResearchRepository
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ResearchRepositoryImpl @Inject constructor(private val gateway: BackendGateway) : ResearchRepository {
    override suspend fun chart(conid: Long, period: String): Result<ChartData> =
        apiCall { gateway.api().chart(conid, period).toDomain() }

    override suspend fun overview(conid: Long): Result<CompanyOverview> =
        apiCall { gateway.api().overview(conid).toDomain() }

    override suspend fun ipos(market: String): Result<IpoList> =
        apiCall { gateway.api().ipos(market).toDomain() }

    override suspend fun ipoDetail(symbol: String, series: String, name: String?) =
        apiCall { gateway.api().ipoDetail(symbol, series, name).toDomain() }

    override suspend fun marketOverview() = apiCall { gateway.api().marketOverview().toDomain() }

    override suspend fun portfolioHistory(currency: String, days: Int) =
        apiCall { gateway.api().portfolioHistory(currency, days).toDomain() }

    override suspend fun briefing(lang: String) =
        apiCall { gateway.api().briefing(lang).let { com.quietstack.voicetrade.domain.model.Briefing(it.title, it.text) } }
}

@Singleton
class AlertsRepositoryImpl @Inject constructor(private val gateway: BackendGateway) : AlertsRepository {
    override suspend fun list() = apiCall { gateway.api().alerts().map { it.toDomain() } }
    override suspend fun add(conid: Long, target: String, direction: String?) =
        apiCall { gateway.api().addAlert(com.quietstack.voicetrade.data.remote.dto.AddAlertRequest(conid, target, direction)).id }
    override suspend fun cancel(id: Long) = apiCall { gateway.api().cancelAlert(id) }
    override suspend fun pending() = apiCall { gateway.api().pendingAlerts().map { it.toDomain() } }
    override suspend fun ack(ids: List<Long>) = apiCall { gateway.api().ackAlerts(com.quietstack.voicetrade.data.remote.dto.AckRequest(ids)) }
}
