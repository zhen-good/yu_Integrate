package com.example.thelastone.data.repo.impl

import android.util.Log
import com.example.thelastone.data.local.MessageDao
import com.example.thelastone.data.local.MessageEntity
import com.example.thelastone.data.local.SendStatus
import com.example.thelastone.data.model.Message
import com.example.thelastone.data.model.PlaceLite
import com.example.thelastone.data.model.User
import com.example.thelastone.data.remote.AnalyzeBody
import com.example.thelastone.data.remote.ChatService
import com.example.thelastone.data.remote.ChatWebSocketService
import com.example.thelastone.data.remote.MessageDto
import com.example.thelastone.data.remote.SocketEvent
import com.example.thelastone.data.repo.ChatRepository
import com.example.thelastone.di.SessionManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ChatRepositoryImpl @Inject constructor(
    private val service: ChatService,
    private val dao: MessageDao,
    private val json: Json,
    private val session: SessionManager,
    private val webSocketService: ChatWebSocketService,
) : ChatRepository {

    companion object {
        private const val TAG = "ChatRepository"
    }

    private val repositoryScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var currentTripId: String? = null
    private val _isConnected = MutableStateFlow(false)
    private val _realtimeMessages = MutableStateFlow<List<Message>>(emptyList())

    init {
        Log.d(TAG, "===== ChatRepositoryImpl 初始化 =====")
        startListeningToSocketEvents()
    }

    // ✅ 實現 connect 方法
    override suspend fun connect(tripId: String, userId: String, username: String) {
        Log.d(TAG, "🔌 connect() 被調用")
        Log.d(TAG, "  tripId: $tripId")
        Log.d(TAG, "  userId: $userId")
        Log.d(TAG, "  username: $username")

        currentTripId = tripId

        // 等待連線成功後加入房間
        _isConnected
            .filter { it }
            .take(1)
            .collect {
                Log.d(TAG, "✅ 連線成功，加入房間")
                joinRoom(tripId, username, userId)
            }
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
                        Log.d(TAG, "  from: ${event.message.username}")

                        currentTripId?.let { tripId ->
                            val message = Message(
                                id = event.message.id,
                                tripId = tripId,
                                sender = User(
                                    id = event.message.userId,
                                    name = event.message.username,
                                    email = "",  // ✅ WebSocket 訊息沒有 email，用空字串
                                    avatarUrl = null,
                                    friends = emptyList()
                                ),
                                text = event.message.content,
                                timestamp = event.message.timestamp,
                                isAi = false,
                                suggestions = null
                            )
                        val currentMessages = _realtimeMessages.value.toMutableList()
                        currentMessages.add(message)
                        _realtimeMessages.value = currentMessages

                        Log.d(TAG, "✅ 訊息已加入列表，總數: ${currentMessages.size}")
                        }
                    }

                    is SocketEvent.SystemMessage -> {
                        Log.d(TAG, "📢 收到系統訊息: ${event.message}")

                        val systemMessage = Message(
                            id = System.currentTimeMillis().toString(),
                            tripId = currentTripId ?: "",
                            sender = User(                    // ← 直接創建 User
                                id = "system",
                                name = "系統",
                                email = "",
                                avatarUrl = null,
                                friends = emptyList()
                            ),
                            text = event.message,  // ← String 賦值給 text: String
                            timestamp = System.currentTimeMillis(),
                            isAi = true,
                            suggestions = null
                        )

                        val currentMessages = _realtimeMessages.value.toMutableList()
                        currentMessages.add(systemMessage)
                        _realtimeMessages.value = currentMessages

                        Log.d(TAG, "✅ 系統訊息已加入，總數: ${currentMessages.size}")
                    }

                    else -> {
                        Log.d(TAG, "ℹ️ 忽略事件: ${event::class.simpleName}")
                    }
                }
            }
        }
    }

    override fun connectToChat(): Flow<SocketEvent> {
        return webSocketService.connect()
    }

    override fun joinRoom(tripId: String, username: String, userId: String) {
        Log.d(TAG, "===== joinRoom =====")
        Log.d(TAG, "  tripId: $tripId")
        Log.d(TAG, "  username: $username")
        Log.d(TAG, "  userId: $userId")
        Log.d(TAG, "  isConnected: ${_isConnected.value}")

        currentTripId = tripId

        if (_isConnected.value) {
            webSocketService.joinRoom(tripId, username, userId)
            Log.d(TAG, "✅ joinRoom 已呼叫")
        } else {
            Log.w(TAG, "⚠️ WebSocket 未連線，無法加入房間")
        }
    }

    override fun leaveRoom(tripId: String, username: String) {
        Log.d(TAG, "===== leaveRoom =====")
        if (currentTripId == tripId) {
            currentTripId = null
        }
        webSocketService.leaveRoom(tripId, username)
    }

    override fun sendTypingStatus(tripId: String, username: String, isTyping: Boolean) {
        webSocketService.sendTypingStatus(tripId, username, isTyping)
    }

    override fun disconnect() {
        Log.d(TAG, "===== disconnect =====")
        webSocketService.disconnect()
        currentTripId = null
        _isConnected.value = false
        _realtimeMessages.value = emptyList()
    }

    override fun observeMessages(tripId: String): Flow<List<Message>> {
        Log.d(TAG, "===== observeMessages =====")
        Log.d(TAG, "  tripId: $tripId")
        return _realtimeMessages.asStateFlow()
    }

    override fun sendMessage(userId: String, tripId: String, message: String) {
        Log.d(TAG, "📤 準備發送訊息: $message")
        Log.d(TAG, "  userId: $userId")
        Log.d(TAG, "  tripId: $tripId")
        Log.d(TAG, "  isConnected: ${_isConnected.value}")

        if (_isConnected.value) {
            webSocketService.sendMessage(tripId, userId, message)
            Log.d(TAG, "✅ 訊息已發送")
        } else {
            Log.e(TAG, "❌ WebSocket 未連線，無法發送訊息")
        }
    }

    override suspend fun analyze(tripId: String) {
        Log.d(TAG, "===== analyze =====")

        val history = dao.observeByTrip(tripId).first()
        val dtoHistory = history.map {
            MessageDto(
                id = it.id,
                tripId = it.tripId,
                senderId = it.senderId,
                senderName = it.senderName,
                text = it.text,
                timestamp = it.timestamp,
                isAi = it.isAi,
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