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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
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
        val myId: String                        // ← 新增
    ) : ChatUiState
    data class Error(val message: String) : ChatUiState
}


@HiltViewModel
class TripChatViewModel @Inject constructor(
    private val chatRepo: ChatRepository,
    tripRepo: TripRepository,
    session: SessionManager,                // ← 去掉 private val
    savedStateHandle: SavedStateHandle
) : ViewModel() {

    private val tripId: String = savedStateHandle["tripId"] ?: error("tripId missing")

    private val _input = MutableStateFlow("")
    private val _analyzing = MutableStateFlow(false)
    private val _showTripSheet = MutableStateFlow(false)

    // 這裡示範：沒有 session 就先用 demoId；若有 session，改為 session.auth.map { it?.user?.id ?: DEMO_USER.id }
    private val myIdFlow: Flow<String> =
        session.auth.map { it?.user?.id ?: "guest" }

    private val tripFlow: Flow<Trip?> =
        tripRepo.observeTripDetail(tripId).map<Trip, Trip?> { it }.catch { emit(null) }

    private val messagesFlow: Flow<List<Message>> =
        chatRepo.observeMessages(tripId)
            .catch { emit(emptyList()) }


    // 1) 先把跟畫面直接關的 5 條 flow combine 成一個 bits
    private data class UiBits(
        val trip: Trip?,
        val messages: List<Message>,
        val input: String,
        val analyzing: Boolean,
        val showTripSheet: Boolean
    )

    private val bitsFlow: Flow<UiBits> =
        combine(tripFlow, messagesFlow, _input, _analyzing, _showTripSheet) { trip, msgs, input, analyzing, sheet ->
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
            // 官方建議：畫面可見時才共享，離開後延遲取消，避免多餘訂閱
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = ChatUiState.Loading
        )

    init {
        viewModelScope.launch { chatRepo.refresh(tripId) }
    }

    fun updateInput(v: String) { _input.value = v }

    fun send() = viewModelScope.launch {
        val txt = _input.value.trim()
        if (txt.isEmpty()) return@launch
        _input.value = ""
        try { chatRepo.send(tripId, txt) } catch (_: Exception) { }
    }

    private var analyzeJob: Job? = null
    fun analyze() {
        if (_analyzing.value) return
        analyzeJob?.cancel()
        analyzeJob = viewModelScope.launch {
            _analyzing.value = true
            try { chatRepo.analyze(tripId) } finally { _analyzing.value = false }
        }
    }

    fun toggleTripSheet(show: Boolean) { _showTripSheet.value = show }

    fun onSelectSuggestion(place: PlaceLite) = viewModelScope.launch {
        chatRepo.send(tripId, "選擇：${place.name}")
    }
}
