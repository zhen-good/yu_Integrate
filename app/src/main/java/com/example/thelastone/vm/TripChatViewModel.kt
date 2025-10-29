package com.example.thelastone.vm

import android.util.Log
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.thelastone.data.model.ChoiceOption
import com.example.thelastone.data.model.Message
import com.example.thelastone.data.model.PlaceLite
import com.example.thelastone.data.model.SingleChoiceQuestion
import com.example.thelastone.data.model.Trip
import com.example.thelastone.data.repo.ChatRepository
import com.example.thelastone.data.repo.TripRepository
import com.example.thelastone.di.SessionManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject


sealed interface ChatUiState {
    data object Loading : ChatUiState
    data class Data(
        val trip: Trip?,
        val messages: List<Message>,
        val input: String,
        val analyzing: Boolean,
        val showTripSheet: Boolean,
        val myId: String,
        val tripLoading: Boolean
    ) : ChatUiState
    data class Error(val message: String) : ChatUiState
}

@HiltViewModel
class TripChatViewModel @Inject constructor(
    private val chatRepo: ChatRepository,
    // ❌ 移除 TripRepository
    // private val tripRepo: TripRepository,
    private val session: SessionManager,
    savedStateHandle: SavedStateHandle
) : ViewModel() {
    private val tripId: String = savedStateHandle["tripId"] ?: "test_trip_123"
    private val _input = MutableStateFlow("")
    private val _analyzing = MutableStateFlow(false)
    private val _showTripSheet = MutableStateFlow(false)

    // ✅ 保持 VM 內部的 trip Flow，它將由 ChatRepository 的事件來更新
    private val _tripFlow = MutableStateFlow<Trip?>(null)
    private val _tripLoading = MutableStateFlow(false)
    private val myIdFlow: Flow<String> =
        session.auth.map { it?.user?.id ?: "guest" }


    private val messagesFlow: Flow<List<Message>> =
        chatRepo.observeMessages(tripId)
            .catch { emit(emptyList()) }

    private data class UiBits(
        val trip: Trip?,
        val messages: List<Message>,
        val input: String,
        val analyzing: Boolean,
        val showTripSheet: Boolean,
        val tripLoading: Boolean
    )

    private val bitsFlow: Flow<UiBits> =
        combine(
            _tripFlow, // ✅ 監聽內部的 _tripFlow
            messagesFlow,
            _input,
            _analyzing,
            _showTripSheet
        ) { trip, messages, input, analyzing, sheet ->
            UiBits(
                trip = trip,
                messages = messages,
                input = input,
                analyzing = analyzing,
                showTripSheet = sheet,
                tripLoading = false
            )
        }.combine(_tripLoading) { bitsWithDummy, actualTripLoading ->
            bitsWithDummy.copy(tripLoading = actualTripLoading)
        }

    val state: StateFlow<ChatUiState> =
        combine(bitsFlow, myIdFlow) { bits, myId ->
            ChatUiState.Data(
                trip = bits.trip,
                messages = bits.messages,
                input = bits.input,
                analyzing = bits.analyzing,
                showTripSheet = bits.showTripSheet,
                myId = myId,
                tripLoading = bits.tripLoading
            )
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = ChatUiState.Loading
        )

    init {
        Log.d("TripChatVM", "🚀 初始化 - tripId: $tripId")
        viewModelScope.launch {
            try {
                val auth = session.auth.first()
                val userId = auth?.user?.id ?: "guest"
                val username = auth?.user?.name ?: "Guest"
                Log.d("TripChatVM", "👤 使用者: $userId / $username")

                chatRepo.connect(
                    tripId = tripId,
                    userId = userId,
                    username = username
                )
                Log.d("TripChatVM", "✅ WebSocket 已連接並加入房間")
            } catch (e: Exception) {
                Log.e("TripChatVM", "❌ 連接失敗", e)
            }
        }

        // ✅ 關鍵：新增一個 collector 來監聽來自 Repository 的行程事件
        viewModelScope.launch {
            chatRepo.tripEventFlow.collect { tripData ->
                Log.d("TripChatVM", "✅ 收到 Repo 傳來的行程事件: ${tripData.name}")
                // 1. 更新內部的行程狀態
                _tripFlow.value = tripData
                // 2. 觸發 BottomSheet 顯示
                _showTripSheet.value = true
            }
        }
    }

    fun updateInput(v: String) {
        _input.value = v
    }

    fun send() = viewModelScope.launch {
        val txt = _input.value.trim()
        if (txt.isEmpty()) return@launch
        _input.value = ""
        try {
            val userId = session.auth.first()?.user?.id
            if (userId.isNullOrEmpty()) {
                Log.e("ChatVM", "❌ 使用者未登入")
                return@launch
            }
            chatRepo.sendMessage(
                userId = userId,
                tripId = tripId,
                message = txt
            )
            Log.d("ChatVM", "✅ 訊息已發送: $txt")
        } catch (e: Exception) {
            Log.e("ChatVM", "❌ 發送失敗: ${e.message}", e)
        }
    }

    private var analyzeJob: Job? = null
    fun analyze() {
        if (_analyzing.value) return
        analyzeJob?.cancel()
        analyzeJob = viewModelScope.launch {
            _analyzing.value = true
            try {
                chatRepo.analyze(tripId)
            } finally {
                _analyzing.value = false
            }
        }
    }
    fun toggleTripSheet(show: Boolean) {
        _showTripSheet.value = show
    }
    fun onSelectSuggestion(place: PlaceLite) = viewModelScope.launch {
        val userId = session.auth.first()?.user?.id
        if (userId.isNullOrEmpty()) {
            Log.e("ChatVM", "❌ 使用者未登入")
            return@launch
        }

        chatRepo.sendMessage(
            userId = userId,
            tripId = tripId,
            message = "選擇：${place.name}"
        )
    }

    fun onSelectQuestionOption(question: SingleChoiceQuestion, option: ChoiceOption) {
        val currentState = state.value
        val userId = (currentState as? ChatUiState.Data)?.myId
        val messageText = option.label

        if (userId.isNullOrEmpty()) {
            Log.e("ChatVM", "❌ 使用者 ID 為空，無法發送選項回覆")
            return
        }
        viewModelScope.launch {
            chatRepo.sendQuestionAnswer(
                tripId = tripId,
                questionId = question.id,
                value = option.value ?: option.label
            )
            chatRepo.sendMessage(
                userId = userId,
                tripId = tripId,
                message = messageText
            )
        }
    }

    // ✅ 關鍵：修改 loadTripAndShowSheet
    private var loadTripJob: Job? = null
    fun loadTripAndShowSheet() {
        // 如果正在載入中，或 BottomSheet 已經顯示，就不要重複執行
        if (_tripLoading.value || _showTripSheet.value) return
        loadTripJob?.cancel()
        loadTripJob = viewModelScope.launch {
            _tripLoading.value = true
            try {
                chatRepo.requestTripData(tripId)

            } catch (e: Exception) {
                Log.e("TripChatVM", "❌ 請求行程失敗 (發送指令時)", e)
            } finally {
                // 4. 請求發送出去後，就停止 loading
                // (我們現在進入 "等待" Socket 回應的狀態)
                _tripLoading.value = false
            }
        }
    }

    fun onButtonClick(buttonValue: String) = viewModelScope.launch {
        val userId = session.auth.first()?.user?.id
        if (userId.isNullOrEmpty()) {
            Log.e("ChatVM", "❌ 使用者 ID 為空，無法發送按鈕回覆")
            return@launch
        }
        try {
            chatRepo.sendMessage(
                userId = userId,
                tripId = tripId,
                message = buttonValue
            )
            Log.d("ChatVM", "✅ 按鈕值已發送: $buttonValue")

        } catch (e: Exception) {
            Log.e("ChatVM", "❌ 發送按鈕值失敗: ${e.message}", e)
        }
    }
}