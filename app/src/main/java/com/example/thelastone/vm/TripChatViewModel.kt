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
    session: SessionManager,
    savedStateHandle: SavedStateHandle
) : ViewModel() {

    private val tripId: String = savedStateHandle["tripId"] ?: "test_trip_123"

    private val _input = MutableStateFlow("")
    private val _analyzing = MutableStateFlow(false)
    private val _showTripSheet = MutableStateFlow(false)

    private val myIdFlow: Flow<String> =
        session.auth.map { it?.user?.id ?: "guest" }

    // 🔧 暫時不載入 Trip，只專注在聊天功能
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
        viewModelScope.launch {
            chatRepo.refresh(tripId)
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
            chatRepo.send(tripId, txt)
        } catch (e: Exception) {
            // 錯誤處理
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
        chatRepo.send(tripId, "選擇：${place.name}")
    }
}