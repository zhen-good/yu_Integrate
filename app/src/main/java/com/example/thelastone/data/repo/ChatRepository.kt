// data/repo/ChatRepository.kt
package com.example.thelastone.data.repo

import com.example.thelastone.data.model.Message
import com.example.thelastone.data.remote.ChatMessage
import com.example.thelastone.data.remote.SocketEvent
import kotlinx.coroutines.flow.Flow

interface ChatRepository {
    // WebSocket 方法
    fun connectToChat(): Flow<SocketEvent>
    fun joinRoom(tripId: String, username: String, userId: String)
    fun leaveRoom(tripId: String, username: String)
    fun sendTypingStatus(tripId: String, username: String, isTyping: Boolean)
    fun disconnect()

    // 原有的方法 (保留你的業務邏輯)
    fun observeMessages(tripId: String): Flow<List<Message>>
    suspend fun refresh(tripId: String)
    suspend fun send(tripId: String, content: String)
    suspend fun analyze(tripId: String)
}