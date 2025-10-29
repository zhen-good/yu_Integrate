package com.example.thelastone.vm

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.thelastone.data.model.FriendRequest
import com.example.thelastone.data.model.User
import com.example.thelastone.data.repo.FriendRepository
import com.example.thelastone.data.repo.UserRepository
import com.example.thelastone.di.SessionManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class IncomingItem(
    val request: FriendRequest,
    val fromUser: User
)

data class FriendsUiState(
    val loading: Boolean = true,
    val incoming: List<IncomingItem> = emptyList(),
    val friends: List<User> = emptyList(),
    val error: String? = null
)

@HiltViewModel
class FriendsViewModel @Inject constructor(
    private val repo: UserRepository,
    private val Friendrepo: FriendRepository,
    private val session: SessionManager  //從這邊獲取當前的使用者資訊
) : ViewModel() {
    //friends的資料來源是userRepository的getFriends()
    //incoming的資料來源是userRepository的getIncomingFriendRequests()

    private val _state = MutableStateFlow(FriendsUiState())
    val state: StateFlow<FriendsUiState> = _state

    init { refresh() }

    fun refresh() {
        viewModelScope.launch {
            _state.value = _state.value.copy(loading = true, error = null)
            try {
                val incomingReqs = repo.getIncomingFriendRequests()
                val incoming = incomingReqs.mapNotNull { req ->
                    val from = repo.getUserById(req.fromUserId) ?: return@mapNotNull null
                    IncomingItem(req, from)
                }
                // ← 從 SessionManager 取得真實的 userId
                val currentUserId = session.currentUserId // 或其他方法名稱
                val friends = Friendrepo.getFriends(userId = currentUserId)
                _state.value = FriendsUiState(loading = false, incoming = incoming, friends = friends)
            } catch (e: Exception) {
                _state.value = _state.value.copy(loading = false, error = e.message ?: "Unknown error")
            }
        }
    }

    fun accept(requestId: String) {
        viewModelScope.launch {
            repo.acceptFriendRequest(requestId)
            refresh()
        }
    }

    fun reject(requestId: String) {
        viewModelScope.launch {
            repo.rejectFriendRequest(requestId)
            refresh()
        }
    }
}