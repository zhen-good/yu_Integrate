package com.example.thelastone.vm

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.thelastone.data.model.Message
import com.example.thelastone.data.model.PlaceLite
import com.example.thelastone.data.model.Trip
import com.example.thelastone.data.repo.ChatRepository
import com.example.thelastone.data.repo.TripRepository
import com.example.thelastone.di.SessionManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import android.util.Log
import javax.inject.Inject

sealed interface ChatUiState {
    data object Loading : ChatUiState
    data class Data(
        val trip: Trip?,
        val messages: List<Message>,
        val input: String,
        val analyzing: Boolean,
        val showTripSheet: Boolean,
        val myId: String
    ) : ChatUiState
    data class Error(val message: String) : ChatUiState
}

@HiltViewModel
class TripChatViewModel @Inject constructor(
    private val chatRepo: ChatRepository,
    tripRepo: TripRepository,
    private val session: SessionManager, // ✅ 加 private
    savedStateHandle: SavedStateHandle
) : ViewModel() {

    private val tripId: String = savedStateHandle["tripId"] ?: "test_trip_123"

    private val _input = MutableStateFlow("")
    private val _analyzing = MutableStateFlow(false)
    private val _showTripSheet = MutableStateFlow(false)

    private val myIdFlow: Flow<String> =
        session.auth.map { it?.user?.id ?: "guest" }

    private val tripFlow: Flow<Trip?> = flowOf(null)

    private val messagesFlow: Flow<List<Message>> =
        chatRepo.observeMessages(tripId)
            .catch { emit(emptyList()) }

    private data class UiBits(
        val trip: Trip?,
        val messages: List<Message>,
        val input: String,
        val analyzing: Boolean,
        val showTripSheet: Boolean
    )

    private val bitsFlow: Flow<UiBits> =
        combine(
            tripFlow,
            messagesFlow,
            _input,
            _analyzing,
            _showTripSheet
        ) { trip, msgs, input, analyzing, sheet ->
            UiBits(trip, msgs, input, analyzing, sheet)
        }

    val state: StateFlow<ChatUiState> =
        combine(bitsFlow, myIdFlow) { bits, myId ->
            ChatUiState.Data(
                trip = bits.trip,
                messages = bits.messages,
                input = bits.input,
                analyzing = bits.analyzing,
                showTripSheet = bits.showTripSheet,
                myId = myId
            )
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = ChatUiState.Loading
        )

    init {
        Log.d("TripChatVM", "🚀 初始化 - tripId: $tripId")

        // ✅ 啟動 WebSocket 連接
        viewModelScope.launch {
            try {
                // 取得使用者資訊
                val auth = session.auth.first()
                val userId = auth?.user?.id ?: "guest"
                val username = auth?.user?.name ?: "Guest"

                Log.d("TripChatVM", "👤 使用者: $userId / $username")

                // ✅ 關鍵：連接並加入房間
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
    }

    fun updateInput(v: String) {
        _input.value = v
    }

    // ✅ 修正 send() 方法
    fun send() = viewModelScope.launch {
        val txt = _input.value.trim()
        if (txt.isEmpty()) return@launch

        _input.value = ""

        try {
            // 取得當前使用者 ID
            val userId = session.auth.first()?.user?.id

            if (userId.isNullOrEmpty()) {
                Log.e("ChatVM", "❌ 使用者未登入")
                return@launch
            }

            // ✅ 使用新的 sendMessage 方法
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
}