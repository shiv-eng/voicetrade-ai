package com.quietstack.voicetrade.data.remote

import com.quietstack.voicetrade.core.common.AppError
import com.quietstack.voicetrade.data.remote.dto.ApiErrorDto
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerializationException
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import retrofit2.HttpException
import java.io.IOException

object AppJson {
    val instance: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
        classDiscriminator = "kind"
    }
}

/** Wraps a backend call so every failure becomes an [AppError] inside a [Result]. */
suspend inline fun <T> apiCall(crossinline block: suspend () -> T): Result<T> = try {
    Result.success(block())
} catch (e: CancellationException) {
    throw e
} catch (e: Throwable) {
    Result.failure(e.toApiError())
}

fun Throwable.toApiError(): AppError = when (this) {
    is AppError -> this
    is HttpException -> {
        val body = runCatching {
            response()?.errorBody()?.string()?.let { AppJson.instance.decodeFromString<ApiErrorDto>(it) }
        }.getOrNull()
        mapCode(code(), body?.code, body?.message)
    }
    is IOException -> AppError.Network(this)
    is SerializationException -> AppError.Unknown("Unexpected response from the backend", this)
    else -> AppError.Unknown(message, this)
}

fun mapCode(http: Int, code: String?, message: String?): AppError = when {
    code == "BROKER_LOGGED_OUT" -> AppError.BrokerLoggedOut()
    code == "MARKET_DATA_UNAVAILABLE" -> AppError.MarketDataUnavailable()
    code == "BAD_PAIRING_CODE" -> AppError.BadPairingCode()
    code == "AGENT_FAILED" -> AppError.AgentFailed(message)
    code == "PREVIEW_EXPIRED" -> AppError.PreviewExpired()
    code == "PRICE_DRIFT" -> AppError.PriceDrift()
    code == "KILL_SWITCH" || http == 423 -> AppError.KillSwitch()
    code == "RISK_BLOCKED" -> AppError.RiskBlocked(message)
    http == 401 || http == 403 -> AppError.Unauthorized()
    http == 503 && code == null -> AppError.BrokerLoggedOut()
    else -> AppError.Unknown(message ?: "HTTP $http")
}
