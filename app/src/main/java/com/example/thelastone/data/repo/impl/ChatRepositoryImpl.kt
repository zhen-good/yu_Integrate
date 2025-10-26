package com.example.thelastone.data.repo.impl

import android.util.Log
import com.example.thelastone.data.local.MessageDao
import com.example.thelastone.data.local.MessageEntity
import com.example.thelastone.data.local.SendStatus
import com.example.thelastone.data.mapper.QuestionMapper
import com.example.thelastone.data.model.LegacyQuestionDto
import com.example.thelastone.data.model.Message
import com.example.thelastone.data.model.PlaceLite
import com.example.thelastone.data.model.QuestionAnswerDto
import com.example.thelastone.data.model.QuestionV2Dto
import com.example.thelastone.data.model.SingleChoiceQuestion
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
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject


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




    @Serializable
    data class AiQuestionEnvelope(
        @SerialName("user_id") val userId: String? = null,
        // ✅ 關鍵修正：直接映射到你的 DTO
        val message: QuestionV2Dto? = null
    )




    //gemini說他沒有被用到
//    private fun parseQuestion(raw: String): SingleChoiceQuestion? {
//        val s = raw.trim()
//        // 非 JSON 直接略過
//        if (s.isEmpty() || (s[0] != '{' && s[0] != '[')) {
//            Log.d(TAG, "parseQuestion: 非 JSON，略過（${s.take(40)}…）")
//            return null
//        }
//        return runCatching {
//            json.decodeFromString<QuestionV2Dto>(s)
//                .let(QuestionMapper::fromV2)
//        }.recoverCatching { e1 ->
//            Log.w(TAG, "V2 直送解析失敗，改試信封：${e1.message}")
//            val env = json.decodeFromString<AiQuestionEnvelope>(s)
//            val msg = env.message ?: error("Envelope missing message")
//            if (msg is JsonObject) {
//                json.decodeFromString<QuestionV2Dto>(msg.toString())
//                    .let(QuestionMapper::fromV2)
//            } else error("Envelope message is not an object")
//        }.recoverCatching { e2 ->
//            Log.w(TAG, "信封解析失敗，改試 Legacy：${e2.message}")
//            json.decodeFromString<LegacyQuestionDto>(s)
//                .let(QuestionMapper::fromLegacy) ?: error("Legacy 內容不完整")
//        }.onFailure { e ->
//            Log.e(TAG, "❌ parseQuestion 失敗：${e.message}\nraw=${s.take(200)}…", e)
//        }.getOrNull()
//    }

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
                            val msg = Message(
                                id = event.message.id,
                                tripId = tripId,
                                sender = User(
                                    id = event.message.userId,
                                    name = event.message.username,
                                    email = "",
                                    avatarUrl = null,
                                    friends = emptyList()
                                ),
                                text = event.message.content,
                                timestamp = event.message.timestamp,
                                isAi = false,
                                suggestions = null,
                                isQuestion = false,
                                question = null
                            )
                            val list = _realtimeMessages.value.toMutableList()
                            list.add(msg)
                            _realtimeMessages.value = list
                            Log.d(TAG, "✅ 訊息已加入列表，總數: ${list.size}")
                        }
                    }

                    is SocketEvent.AiQuestionV2 -> {
                        // 假設 SocketEvent.AiQuestionV2 確實有一個名為 rawJson 的屬性
                        val rawJson = event.rawJson // 確保 event 裡有這個屬性，否則這裡會報錯

                        Log.d(TAG, "🧩 收到 ai_question_v2: $rawJson")

                        val questionDto = try {
                            // ✅ 修正：移除具名參數 'string ='
                            val envelope = json.decodeFromString<AiQuestionEnvelope>(rawJson)
                            envelope.message // 取得 QuestionV2Dto?
                        } catch (e: Exception) {
                            Log.e(TAG, "❌ AiQuestionV2 JSON 解析失敗", e)
                            null
                        }

                        val domainQuestion = questionDto?.let(QuestionMapper::fromV2)

                        if (domainQuestion != null) {
                            val id = UUID.randomUUID().toString()
                            val newMessage = Message(
                                id = id,
                                tripId = currentTripId ?: "",
                                sender = User("ai", "Trip AI", "", null, emptyList()),
                                text = domainQuestion.text,
                                timestamp = System.currentTimeMillis(),
                                isAi = true,
                                singleChoiceQuestion = domainQuestion, // 成功賦值
                                suggestions = null
                            )

                            // ✅ 修正：將新訊息添加到狀態流
                            val list = _realtimeMessages.value.toMutableList()
                            list.add(newMessage)
                            _realtimeMessages.value = list
                            Log.d(TAG, "✅ 題目訊息已加入，總數: ${list.size}")
                        }
                    }

                    is SocketEvent.SystemMessage -> {
                        Log.d(TAG, "📢 收到系統訊息: ${event.message}")

                        // ❌ 刪除：移除所有 runCatching 解析題目的邏輯
                        // ...

                        // 只需要創建一般系統文字訊息即可
                        val msg = Message(
                            id = System.currentTimeMillis().toString(),
                            tripId = currentTripId ?: "",
                            sender = User("system", "Trip AI", "", null, emptyList()),
                            text = event.message,
                            timestamp = System.currentTimeMillis(),
                            isAi = true,
                            suggestions = null,
                            // ❌ 移除這兩個不必要的欄位
                            // isQuestion = false,
                            // question = null,
                        )

                        val list = _realtimeMessages.value.toMutableList()
                        list.add(msg)
                        _realtimeMessages.value = list
                        Log.d(TAG, "✅ 系統訊息 (文本) 已加入，總數: ${list.size}")
                    }
                    // ✅ 修正：加入 else 分支來處理所有其他未列出的 SocketEvent
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

    override suspend fun sendQuestionAnswer(
        tripId: String,
        questionId: String,
        value: String
    ) {
        // 1. 建立包含答案的 JSON Payload
        // 1. 建立包含答案的 JSON Payload
        val answerPayload = json.encodeToString( // ✅ 修正：使用小寫 'json' 實例
            QuestionAnswerDto(
                questionId = questionId,
                answer = value
            )
        )

        // 2. 透過 Socket 服務發送 "send_answer" 事件給後端
        webSocketService.emit("send_answer", answerPayload, tripId) // ✅ 修正！

        // ⚠️ 注意：你需要確保 ChatWebSocketService 有一個 emit 函式，例如：
        /*
        // ChatWebSocketService.kt
        fun emit(event: String, data: String, room: String) {
            socket.emit(event, data, room) // 假設 socket.io 支援 room
        }
        */
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
