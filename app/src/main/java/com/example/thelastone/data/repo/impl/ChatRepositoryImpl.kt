package com.example.thelastone.data.repo.impl

import com.example.thelastone.data.local.MessageDao
import com.example.thelastone.data.local.MessageEntity
import com.example.thelastone.data.local.SendStatus
import com.example.thelastone.data.local.toEntity
import com.example.thelastone.data.local.toModel
import com.example.thelastone.data.model.Message
import com.example.thelastone.data.model.PlaceLite
import com.example.thelastone.data.model.User
import com.example.thelastone.data.remote.AnalyzeBody
import com.example.thelastone.data.remote.ChatService
import com.example.thelastone.data.remote.MessageDto
import com.example.thelastone.data.remote.SendMessageBody
import com.example.thelastone.data.repo.ChatRepository
import com.example.thelastone.di.SessionManager
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

// data/repo/ChatRepositoryImpl: Repository 的「真實實作」，整合 遠端 API (ChatService) 和 本地資料庫 (MessageDao)
@Singleton
class ChatRepositoryImpl @Inject constructor(
    private val service: ChatService,
    private val dao: MessageDao,
    private val json: Json,
    private val session: SessionManager
) : ChatRepository {

    override fun observeMessages(tripId: String): Flow<List<Message>> =
        dao.observeByTrip(tripId).map { list -> list.map { it.toModel(json) } }

    override suspend fun refresh(tripId: String) {
        val remote = service.getHistory(tripId)
        val entities = remote.map { dto ->
            MessageEntity(
                id = dto.id,
                tripId = dto.tripId,
                senderId = dto.senderId,
                senderName = dto.senderName,
                text = dto.text,
                timestamp = dto.timestamp,
                isAi = dto.isAi,
                status = SendStatus.SENT,
                suggestionsJson = dto.suggestions?.let { json.encodeToString(it) }
            )
        }
        dao.deleteByTrip(tripId)
        dao.upsertAll(entities)
    }

    override suspend fun send(tripId: String, text: String) {
        val me = session.auth.value?.user ?: error("Require login")
        val localId = "local-${UUID.randomUUID()}"

        val localEntity = Message(
            id = localId,
            tripId = tripId,
            sender = me,
            text = text,
            timestamp = System.currentTimeMillis(),
            isAi = false
        ).toEntity(json, SendStatus.SENDING)
        dao.upsert(localEntity)

        try {
            val dto = service.sendMessage(tripId, SendMessageBody(text))
            dao.promoteLocalToServer(localId, dto.id, SendStatus.SENT)
        } catch (e: Exception) {
            dao.updateStatus(localId, SendStatus.FAILED)
            throw e
        }
    }

    override suspend fun analyze(tripId: String) {
        // 從本地拿歷史，傳給後端
        val history = dao.observeByTrip(tripId).first()
        val dtoHistory = history.map {
            MessageDto(
                id = it.id, tripId = it.tripId, senderId = it.senderId,
                senderName = it.senderName, text = it.text,
                timestamp = it.timestamp, isAi = it.isAi,
                suggestions = it.suggestionsJson?.let { s ->
                    json.decodeFromString<List<PlaceLite>>(
                        s
                    )
                }
            )
        }
        val resp = service.analyze(tripId, AnalyzeBody(dtoHistory))

        // 把 AI 訊息寫回本地
        val aiId = "srv-${UUID.randomUUID()}" // 若後端會回 id 就用後端 id
        val ai = MessageEntity(
            id = aiId,
            tripId = tripId,
            senderId = "ai",
            senderName = "Trip AI",
            text = resp.aiText,
            timestamp = System.currentTimeMillis(),
            isAi = true,
            status = SendStatus.SENT,
            suggestionsJson = json.encodeToString(resp.suggestions)
        )
        dao.upsert(ai)
    }
}