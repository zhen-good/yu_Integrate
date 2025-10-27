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
// 導入 DTO
import com.example.thelastone.data.model.TripNodePlaceDto
// 🎯 【新增】必須導入這兩個 DTO，以便在 NewMessage 中解析
import com.example.thelastone.data.model.TripNodeDto
import com.example.thelastone.data.model.SocketTripInner

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

    // ✅ 推薦地點 Flow 的定義
    private val _recommendations = MutableSharedFlow<List<PlaceLite>>(extraBufferCapacity = 1)
    val recommendationsFlow: SharedFlow<List<PlaceLite>> = _recommendations // 確保是 override

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

    //這個是題目板板
    @Serializable
    data class AiQuestionEnvelope(
        @SerialName("user_id") val userId: String? = null,
        // ✅ 關鍵修正：直接映射到你的 DTO
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
                        val messageText = event.message.content
                        Log.d(TAG, "💬 收到新訊息: $messageText")
                        Log.d(TAG, "  from: ${event.message.username}")

                        // 1. 嘗試解析 JSON 結構，判斷是否為推薦結果
                        val tripResponse = try {
                            // 🎯 【修正】移除具名參數 'string ='，避免編譯錯誤
                            json.decodeFromString<SocketTripInner>(messageText)
                        } catch (e: Exception) {
                            // 如果解析失敗，表示這是一個普通的文本消息
                            Log.d(TAG, "ℹ️ NewMessage.content 不是行程 JSON 結構：${e.message}")
                            null
                        }

                        // 2. 提取 PlaceLite 列表
                        val suggestions = tripResponse?.nodes?.flatMap { node ->
                            node.places.map { placeDto ->
                                // 🎯 【修正】使用 DTO 中的 place_id 和 open_text
                                PlaceLite(
                                    placeId = placeDto.place_id,
                                    name = placeDto.name,
                                    lat = placeDto.lat,
                                    lng = placeDto.lng,
                                    rating = placeDto.rating,
                                    // ⚠️ 這裡假設 PlaceLite 的構造函式與 TripNodePlaceDto 的欄位匹配
                                    userRatingsTotal = placeDto.reviews,
                                    address = placeDto.address,
                                    openStatusText = placeDto.open_text // 使用 DTO 欄位
                                )
                            }
                        }

                        // 3. 處理推薦地點的發射
                        if (!suggestions.isNullOrEmpty()) {
                            _recommendations.tryEmit(suggestions)
                            Log.d(TAG, "✅ 推薦地點已從 NewMessage.content 成功發射，數量: ${suggestions.size}")
                        }

                        // 4. 處理聊天訊息本身 (將其存入 _realtimeMessages)
                        currentTripId?.let { tripId ->
                            val textToShow = if (!suggestions.isNullOrEmpty()) {
                                // 如果有推薦，AI 訊息只顯示提示文本
                                "AI 已生成分析結果並提供建議。"
                            } else {
                                messageText
                            }

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
                                text = textToShow,
                                timestamp = event.message.timestamp,
                                isAi = false, // 假設 NewMessage 來自用戶或系統文本
                                suggestions = suggestions,
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
            // 🚨 關鍵：獲取 userId (這是您先前遺漏的參數)
            // 假設您的 Repository 有辦法存取到當前使用者 ID (例如透過 SessionManager 或注入的 AuthRepo)
            val currentUserId = session.currentUserId // <== 確保這裡能拿到 ID

            // 🎯 核心修正：將 analyze 轉換為發送一條普通聊天訊息
            // 注意：根據 ChatRepository 的定義，參數順序可能是 userId, tripId, message

            // 假設您使用的簽名是：
            // override fun sendMessage(userId: String, tripId: String, message: String)

            sendMessage(
                userId = currentUserId, // ✅ 補上使用者 ID
                tripId = tripId,
                message = "分析" // ✅ 這是後端 Socket 期望的特殊指令
            )

            Log.d(TAG, "✅ 成功將 Analyze 請求轉換為 Socket 訊息發送")
        } catch (e: Exception) {
            Log.e(TAG, "❌ 發送 '分析' 命令失敗", e)
            // ... 處理錯誤 ...
        }
    }

    // 🎯 假設您將 SocketTripInner 等 DTO 放置在 data.model 包中，因此這裡不重複定義。
}