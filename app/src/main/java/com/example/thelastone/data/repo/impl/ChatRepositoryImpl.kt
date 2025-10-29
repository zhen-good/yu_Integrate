package com.example.thelastone.data.repo.impl

import android.util.Log
import com.example.thelastone.data.local.MessageDao
import com.example.thelastone.data.mapper.QuestionMapper
import com.example.thelastone.data.model.AiResponsePayload
import com.example.thelastone.data.model.ButtonDto
import com.example.thelastone.data.model.Message
import com.example.thelastone.data.model.PlaceLite
import com.example.thelastone.data.model.QuestionAnswerDto
import com.example.thelastone.data.model.QuestionV2Dto
import com.example.thelastone.data.model.User
// 導入 DTO
// 🎯 【新增】必須導入這兩個 DTO，以便在 NewMessage 中解析
import com.example.thelastone.data.model.SocketTripInner
import com.example.thelastone.data.model.Trip
import com.example.thelastone.data.remote.ChatMessage

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
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.String


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
    private val _recommendations = MutableSharedFlow<List<PlaceLite>>(extraBufferCapacity = 1)
    val recommendationsFlow: SharedFlow<List<PlaceLite>> = _recommendations

    // ✅ 新增：行程事件的 SharedFlow
    private val _tripEventFlow = MutableSharedFlow<Trip>(extraBufferCapacity = 1)
    override val tripEventFlow: SharedFlow<Trip> = _tripEventFlow.asSharedFlow()


    init {
        Log.d(TAG, "===== ChatRepositoryImpl 初始化 =====")
        startListeningToSocketEvents()
    }

    override suspend fun connect(tripId: String, userId: String, username: String) {
        Log.d(TAG, "🔌 connect() 被調用")
        Log.d(TAG, "  tripId: $tripId")
        Log.d(TAG, "  userId: $userId")
        Log.d(TAG, "  username: $username")

        currentTripId = tripId
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
        val message: QuestionV2Dto? = null
    )


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
                        // ... (你原有的 NewMessage 處理邏輯，保持不變) ...
                        val messageText = event.message.content
                        val senderId = event.message.userId
                        Log.d(TAG, "💬 收到新訊息: $messageText")

                        val currentUserId = session.currentUserId

                        if (senderId == currentUserId && currentUserId.isNotBlank()) {
                            addMessageToList(event.message, isAi = false, textToShow = messageText, suggestions = null, buttons = null)
                            Log.d(TAG, "✅ 用戶自己的訊息已加入列表。")
                            return@collect
                        }

                        val aiResponsePayload = try {
                            json.decodeFromString<AiResponsePayload>(messageText)
                        } catch (e: Exception) {
                            null
                        }

                        if (aiResponsePayload != null) {
                            addMessageToList(
                                event.message,
                                isAi = true,
                                textToShow = aiResponsePayload.message,
                                suggestions = null,
                                buttons = aiResponsePayload.buttons
                            )
                            Log.d(TAG, "✅ AI 結構化訊息 (含按鈕) 已加入列表。")
                            return@collect
                        }

                        val tripResponse = try {
                            json.decodeFromString<SocketTripInner>(messageText)
                        } catch (e: Exception) {
                            Log.e(TAG, "❌ AiResponsePayload JSON 解析失敗！", e)
                            Log.d(TAG, "❌ 失敗的 JSON 內容: $messageText")
                            null
                        }

                        val suggestions = emptyList<PlaceLite>()

                        addMessageToList(
                            event.message,
                            isAi = true,
                            textToShow = if (!suggestions.isNullOrEmpty()) "AI 已生成分析結果..." else messageText,
                            suggestions = suggestions,
                            buttons = null
                        )
                        return@collect
                    }

                    is SocketEvent.AiQuestionV2 -> {
                        // ... (你原有的 AiQuestionV2 處理邏輯，保持不變) ...
                        val rawJson = event.rawJson
                        Log.d(TAG, "🧩 收到 ai_question_v2: $rawJson")

                        val questionDto = try {
                            val envelope = json.decodeFromString<AiQuestionEnvelope>(rawJson)
                            envelope.message
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
                                singleChoiceQuestion = domainQuestion,
                                suggestions = null
                            )

                            val list = _realtimeMessages.value.toMutableList()
                            list.add(newMessage)
                            _realtimeMessages.value = list
                            Log.d(TAG, "✅ 題目訊息已加入，總數: ${list.size}")
                        }
                    }

                    is SocketEvent.TripDataReceived -> {
                        val rawJson = event.rawJson
                        Log.d(TAG, "🚢 收到 TripDataReceived，準備解析: $rawJson")

                        // ⚠️
                        val tripData: Trip? = try {
                            json.decodeFromString<Trip>(rawJson)
                        } catch (e: Exception) {
                            Log.e(TAG, "❌ 'trip' 事件 JSON 解析失敗", e)
                            null
                        }

                        if (tripData != null) {
                            Log.d(TAG, "✅ 行程 JSON 解析成功: ${tripData.name}")
                            //
                            repositoryScope.launch {
                                _tripEventFlow.emit(tripData)
                            }
                        } else {
                            Log.w(TAG, "⚠️ 收到 'trip' 事件，但 tripData 為 null 或解析失敗")
                        }
                    }

                    is SocketEvent.SystemMessage -> {
                        Log.d(TAG, "📢 收到系統訊息: ${event.message}")
                        val msg = Message(
                            id = System.currentTimeMillis().toString(),
                            tripId = currentTripId ?: "",
                            sender = User("system", "Trip AI", "", null, emptyList()),
                            text = event.message,
                            timestamp = System.currentTimeMillis(),
                            isAi = true,
                            suggestions = null,
                        )

                        val list = _realtimeMessages.value.toMutableList()
                        list.add(msg)
                        _realtimeMessages.value = list
                        Log.d(TAG, "✅ 系統訊息 (文本) 已加入，總數: ${list.size}")
                    }

                    is SocketEvent.AiResponse -> {
                        // ... (你原有的 AiResponse 處理邏輯，保持不變) ...
                        val rawJson = event.rawJson
                        Log.d(TAG, "🤖 收到 AI 建議 (ai_response): $rawJson")

                        try {
                            val payload = json.decodeFromString<AiResponsePayload>(rawJson)

                            currentTripId?.let { tripId ->
                                val messageTextToShow = payload.message ?: "AI 已完成分析"
                                val aiMessage = Message(
                                    id = UUID.randomUUID().toString(),
                                    tripId = tripId,
                                    sender = User("ai", "Trip AI", "", null, emptyList()),
                                    text = messageTextToShow,
                                    timestamp = System.currentTimeMillis(),
                                    isAi = true,
                                    suggestions = null,
                                    buttons = payload.buttons,
                                    isQuestion = false,
                                    question = null
                                )

                                val list = _realtimeMessages.value.toMutableList()
                                list.add(aiMessage)
                                _realtimeMessages.value = list

                                Log.d(TAG, "✅ AI 文本及按鈕已加入列表。按鈕數: ${payload.buttons?.size ?: 0}")
                            }
                            if (payload.recommendation != null) {
                                Log.d(TAG, "✅ 結構化 AI 建議已儲存 (Type: ${payload.recommendation.type})")
                            }

                        } catch (e: Exception) {
                            Log.e(TAG, "❌ AI 建議 JSON 解析失敗", e)
                        }
                    }

                    else -> {
                        Log.d(TAG, "ℹ️ 忽略事件: ${event::class.simpleName}")
                    }
                }
            }
        }
    }

    private fun addMessageToList(
        socketMessage: ChatMessage,
        isAi: Boolean,
        textToShow: String,
        suggestions: List<PlaceLite>?,
        buttons: List<ButtonDto>?
    ) {
        currentTripId?.let { tripId ->
            val msg = Message(
                id = socketMessage.id,
                tripId = tripId,
                sender = User(
                    id = socketMessage.id,
                    name = socketMessage.username,
                    email = "",
                    avatarUrl = null,
                    friends = emptyList()
                ),
                text = textToShow,
                timestamp = socketMessage.timestamp,
                isAi = isAi,
                suggestions = suggestions,
                buttons = buttons,
                isQuestion = false,
                question = null
            )

            val list = _realtimeMessages.value.toMutableList()
            list.add(msg)
            _realtimeMessages.value = list
            Log.d(TAG, "✅ 訊息已透過輔助函式加入列表，總數: ${list.size}")
        }
    }

    override fun connectToChat(): Flow<SocketEvent> {
        return webSocketService.connect()
    }

    override fun joinRoom(tripId: String, username: String, userId: String) {
        Log.d(TAG, "===== joinRoom =====")
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
        return _realtimeMessages.asStateFlow()
    }

    override fun sendMessage(userId: String, tripId: String, message: String) {
        Log.d(TAG, "📤 準備發送訊息: $message")

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
        val answerPayload = json.encodeToString(
            QuestionAnswerDto(
                questionId = questionId,
                answer = value
            )
        )
        webSocketService.emit("send_answer", answerPayload, tripId)
    }

    override suspend fun analyze(tripId: String) {
        try {
            val currentUserId = session.currentUserId
            sendMessage(
                userId = currentUserId,
                tripId = tripId,
                message = "分析" // ✅ 後端 Socket 期望的特殊指令
            )
            Log.d(TAG, "✅ 成功將 Analyze 請求轉換為 Socket 訊息發送")
        } catch (e: Exception) {
            Log.e(TAG, "❌ 發送 '分析' 命令失敗", e)
        }
    }

    // ✅ 新增：實作 requestTripData
    override suspend fun requestTripData(tripId: String) {
        try {
            val currentUserId = session.currentUserId
            sendMessage(
                userId = currentUserId,
                tripId = tripId,
                message = "行程" // ✅ 後端 Socket 期望的特殊指令
            )
            Log.d(TAG, "✅ 成功將 '行程' 請求轉換為 Socket 訊息發送")
        } catch (e: Exception) {
            Log.e(TAG, "❌ 發送 '行程' 命令失敗", e)
        }
    }
}