package com.quietstack.voicetrade.core.common

import androidx.annotation.StringRes
import com.quietstack.voicetrade.R
import java.io.IOException

/** Every failure the UI can show. Mapped from HTTP/RTC/socket failures in the data layer. */
sealed class AppError(
    @StringRes val messageRes: Int,
    val detail: String? = null,
    val retryable: Boolean = true,
    cause: Throwable? = null,
) : Exception(detail ?: "AppError", cause) {
    class Network(cause: Throwable? = null) : AppError(R.string.err_network, cause = cause)
    class Unauthorized : AppError(R.string.err_unauthorized, retryable = false)
    class BrokerLoggedOut : AppError(R.string.err_broker_logged_out)
    class RtcJoinFailed(code: Int? = null) : AppError(R.string.err_rtc_join, detail = code?.toString())
    class AgentFailed(detail: String? = null) : AppError(R.string.err_agent_failed, detail = detail)
    class RiskBlocked(detail: String?) : AppError(R.string.err_risk_blocked, detail = detail, retryable = false)
    class PreviewExpired : AppError(R.string.err_preview_expired, retryable = false)
    class PriceDrift : AppError(R.string.err_price_drift, retryable = false)
    class MarketDataUnavailable : AppError(R.string.err_market_data)
    class BadPairingCode : AppError(R.string.err_bad_pairing, retryable = false)
    class Unknown(detail: String? = null, cause: Throwable? = null) :
        AppError(R.string.err_unknown, detail = detail, cause = cause)
}

fun Throwable.asAppError(): AppError = when (this) {
    is AppError -> this
    is IOException -> AppError.Network(this)
    else -> AppError.Unknown(message, this)
}
