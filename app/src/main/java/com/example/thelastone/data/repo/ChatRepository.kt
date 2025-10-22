package com.example.thelastone.data.repo

import com.example.thelastone.data.model.Message
import kotlinx.coroutines.flow.Flow

interface ChatRepository {
    fun observeMessages(tripId: String): Flow<List<Message>>
    suspend fun refresh(tripId: String)
    suspend fun send(tripId: String, text: String)    // ← 移除 me
    suspend fun analyze(tripId: String)
}