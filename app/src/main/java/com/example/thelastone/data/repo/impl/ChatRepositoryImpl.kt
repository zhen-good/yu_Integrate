package com.example.thelastone.data.repo.impl

import android.util.Log
import com.example.thelastone.data.local.MessageDao
import com.example.thelastone.data.local.MessageEntity
import com.example.thelastone.data.local.SendStatus
import com.example.thelastone.data.local.toEntity
import com.example.thelastone.data.local.toModel
import com.example.thelastone.data.model.Message
import com.example.thelastone.data.model.PlaceLite
import com.example.thelastone.data.remote.AnalyzeBody
import com.example.thelastone.data.remote.ChatService
import com.example.thelastone.data.remote.ChatWebSocketService
import com.example.thelastone.data.remote.MessageDto
import com.example.thelastone.data.remote.SendMessageBody
import com.example.thelastone.data.remote.SocketEvent
import com.example.thelastone.data.repo.ChatRepository
import com.example.thelastone.di.SessionManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton  // ← 確保有這個
class ChatRepositoryImpl @Inject constructor(  // ← 確保有這個
    private val service: ChatService,
    private val dao: MessageDao,
    private val json: Json,
    private val session: SessionManager,
    private val webSocketService: ChatWebSocketService
) : ChatRepository {

    companion object {
        private const val TAG = "ChatRepository"
    }

    private val repositoryScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var currentTripId: String? = null
    private val _isConnected = MutableStateFlow(false)

    init {
        Log.d(TAG, "===== ChatRepositoryImpl 初始化 =====")
        startListeningToSocketEvents()
    }

    private fun startListeningToSocketEvents() {
        repositoryScope.launch {
            Log.d(TAG, "🎧 開始監聽 WebSocket 事件")
            connectToChat().collect { event ->
                Log.d(TAG, "📨 收到事件: ${event::class.simpleName}")

                when (event) {
                    is SocketEvent.Connected -> {
                        Log.d(TAG, "✅ WebSocket 連接成功")
                        _isConnected.value = true
                    }
                    is SocketEvent.Disconnected -> {
                        Log.d(TAG, "❌ WebSocket 斷線")
                        _isConnected.value = false
                    }
                    is SocketEvent.NewMessage -> {
                        Log.d(TAG, "💬 收到新訊息: ${event.message.content}")
                        currentTripId?.let { tripId ->
                            saveWebSocketMessageToDatabase(tripId, event.message)
                        }
                    }
                    is SocketEvent.SystemMessage -> {
                        Log.d(TAG, "📢 收到系統訊息: ${event.message}")
                        currentTripId?.let { tripId ->
                            saveSystemMessageToDatabase(tripId, event.message)
                        }
                    }
                    else -> {}
                }
            }
        }
    }

    override fun connectToChat(): Flow<SocketEvent> {
        return webSocketService.connect()
    }

    private suspend fun saveWebSocketMessageToDatabase(
        tripId: String,
        chatMessage: com.example.thelastone.data.remote.ChatMessage
    ) {
        try {
            val entity = MessageEntity(
                id = chatMessage.id,
                tripId = tripId,
                senderId = chatMessage.userId,
                senderName = chatMessage.username,
                text = chatMessage.content,
                timestamp = chatMessage.timestamp,
                isAi = false,
                status = SendStatus.SENT,
                suggestionsJson = null
            )
            dao.upsert(entity)
            Log.d(TAG, "✅ WebSocket 訊息已儲存")
        } catch (e: Exception) {
            Log.e(TAG, "❌ 儲存失敗", e)
        }
    }

    private suspend fun saveSystemMessageToDatabase(tripId: String, text: String) {
        try {
            val entity = MessageEntity(
                id = "system-${UUID.randomUUID()}",
                tripId = tripId,
                senderId = "system",
                senderName = "系統",
                text = text,
                timestamp = System.currentTimeMillis(),
                isAi = true,
                status = SendStatus.SENT,
                suggestionsJson = null
            )
            dao.upsert(entity)
            Log.d(TAG, "✅ 系統訊息已儲存")
        } catch (e: Exception) {
            Log.e(TAG, "❌ 儲存失敗", e)
        }
    }

    override fun joinRoom(tripId: String, username: String, userId: String) {
        Log.d(TAG, "===== joinRoom =====")
        currentTripId = tripId
        if (_isConnected.value) {
            webSocketService.joinRoom(tripId, username, userId)
        }
    }

    override fun leaveRoom(tripId: String, username: String) {
        if (currentTripId == tripId) {
            currentTripId = null
        }
        webSocketService.leaveRoom(tripId, username)
    }

    override fun sendTypingStatus(tripId: String, username: String, isTyping: Boolean) {
        webSocketService.sendTypingStatus(tripId, username, isTyping)
    }

    override fun disconnect() {
        webSocketService.disconnect()
        currentTripId = null
        _isConnected.value = false
    }

    // 原本的方法
    override fun observeMessages(tripId: String): Flow<List<Message>> =
        dao.observeByTrip(tripId).map { list -> list.map { it.toModel(json) } }

    override suspend fun refresh(tripId: String) {
        Log.d(TAG, "===== refresh =====")

        // 載入 HTTP 歷史
        try {
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
        } catch (e: Exception) {
            Log.e(TAG, "❌ 載入歷史失敗", e)
        }

        // 加入 WebSocket 房間
        val user = session.auth.value?.user
        val username = user?.name ?: "Guest"
        val userId = user?.id ?: "guest"
        joinRoom(tripId, username, userId)
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
        val history = dao.observeByTrip(tripId).first()
        val dtoHistory = history.map {
            MessageDto(
                id = it.id, tripId = it.tripId, senderId = it.senderId,
                senderName = it.senderName, text = it.text,
                timestamp = it.timestamp, isAi = it.isAi,
                suggestions = it.suggestionsJson?.let { s ->
                    json.decodeFromString<List<PlaceLite>>(s)
                }
            )
        }
        val resp = service.analyze(tripId, AnalyzeBody(dtoHistory))

        val aiId = "srv-${UUID.randomUUID()}"
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