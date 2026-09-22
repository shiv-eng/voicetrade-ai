package com.quietstack.voicetrade.data.repository

import com.quietstack.voicetrade.data.local.db.MessageEntity
import com.quietstack.voicetrade.data.local.db.SessionDao
import com.quietstack.voicetrade.data.local.db.SessionEntity
import com.quietstack.voicetrade.data.mapper.toDomain
import com.quietstack.voicetrade.data.mapper.toDto
import com.quietstack.voicetrade.data.remote.AppJson
import com.quietstack.voicetrade.data.remote.dto.CardDto
import com.quietstack.voicetrade.domain.model.ConversationMessage
import com.quietstack.voicetrade.domain.model.Role
import com.quietstack.voicetrade.domain.model.SessionSummary
import com.quietstack.voicetrade.domain.repository.HistoryRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import timber.log.Timber
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class HistoryRepositoryImpl @Inject constructor(private val dao: SessionDao) : HistoryRepository {

    override fun observeSessions(): Flow<List<SessionSummary>> = dao.observeSessions().map { list ->
        list.map {
            SessionSummary(it.id, Instant.ofEpochMilli(it.startedAt), it.endedAt?.let(Instant::ofEpochMilli), it.summary)
        }
    }

    override fun observeMessages(sessionId: Long): Flow<List<ConversationMessage>> =
        dao.observeMessages(sessionId).map { rows -> rows.map { it.toDomain() } }

    override suspend fun save(startedAtMillis: Long, endedAtMillis: Long, messages: List<ConversationMessage>) {
        val summary = messages.firstOrNull { it.role == Role.USER && !it.text.isNullOrBlank() }?.text?.take(80)
            ?: "${messages.size} messages"
        val rows = messages.map { msg ->
            MessageEntity(
                sessionId = 0,
                messageId = msg.id,
                role = msg.role.name,
                text = msg.text,
                cardJson = msg.card?.let { AppJson.instance.encodeToString<CardDto>(it.toDto()) },
                timestamp = msg.timestamp.toEpochMilli(),
            )
        }
        dao.saveSession(SessionEntity(startedAt = startedAtMillis, endedAt = endedAtMillis, summary = summary), rows)
    }

    override suspend fun delete(sessionId: Long) = dao.delete(sessionId)

    override suspend fun deleteAll() = dao.deleteAll()

    private fun MessageEntity.toDomain() = ConversationMessage(
        id = messageId,
        role = runCatching { Role.valueOf(role) }.getOrDefault(Role.SYSTEM),
        text = text,
        card = cardJson?.let {
            runCatching { AppJson.instance.decodeFromString<CardDto>(it).toDomain() }
                .onFailure { e -> Timber.w(e, "Unreadable stored card") }
                .getOrNull()
        },
        isFinal = true,
        timestamp = Instant.ofEpochMilli(timestamp),
    )
}

