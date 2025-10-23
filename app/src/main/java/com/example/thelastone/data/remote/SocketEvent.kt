// data/remote/ChatWebSocketService.kt
package com.example.thelastone.data.remote
sealed class SocketEvent {
    object Connected : SocketEvent()
    object Disconnected : SocketEvent()
    data class NewMessage(val message: ChatMessage) : SocketEvent()
    data class SystemMessage(val message: String) : SocketEvent()  // ← 新增
    data class UserJoined(val username: String, val message: String) : SocketEvent()
    data class UserLeft(val username: String, val message: String) : SocketEvent()
    data class UserTyping(val username: String, val isTyping: Boolean) : SocketEvent()
    data class Error(val message: String) : SocketEvent()
}