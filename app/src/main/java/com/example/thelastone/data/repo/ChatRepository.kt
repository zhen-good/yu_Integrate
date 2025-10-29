// data/repo/ChatRepository.kt
package com.example.thelastone.data.repo

import com.example.thelastone.data.model.Message
import com.example.thelastone.data.model.Trip
import com.example.thelastone.data.remote.SocketEvent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharedFlow

interface ChatRepository {

    // ✅ 新增：行程事件的 Flow (SharedFlow 用於一對多的事件推播)
    val tripEventFlow: SharedFlow<Trip>

    suspend fun connect(tripId: String, userId: String, username: String)
    fun connectToChat(): Flow<SocketEvent>
    fun joinRoom(tripId: String, username: String, userId: String)
    fun leaveRoom(tripId: String, username: String)
    fun sendTypingStatus(tripId: String, username: String, isTyping: Boolean)
    fun disconnect()
    fun observeMessages(tripId: String): Flow<List<Message>>
    suspend fun analyze(tripId: String)
    suspend fun sendQuestionAnswer(tripId: String, questionId: String, value: String)
    fun sendMessage(userId: String, tripId: String, message: String)

    // ✅ 新增：請求行程資料的方法
    suspend fun requestTripData(tripId: String)
}