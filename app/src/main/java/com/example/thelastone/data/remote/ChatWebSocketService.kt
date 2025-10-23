package com.example.thelastone.data.remote

import android.util.Log
import io.socket.client.IO
import io.socket.client.Socket
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

// data/remote/ChatWebSocketService.kt
@Singleton
class ChatWebSocketService @Inject constructor() {

    companion object {
        private const val TAG = "ChatWebSocket"
    }

    private var socket: Socket? = null
    private val serverUrl = "http://10.0.2.2:5000"

    fun connect(): Flow<SocketEvent> = callbackFlow {
        try {
            Log.d(TAG, "🚀 開始連接到: $serverUrl")
            socket = IO.socket(serverUrl)

            socket?.on(Socket.EVENT_CONNECT) {
                Log.d(TAG, "✅ 連接成功")
                trySend(SocketEvent.Connected)
            }

            socket?.on(Socket.EVENT_DISCONNECT) {
                Log.d(TAG, "❌ 斷線")
                trySend(SocketEvent.Disconnected)
            }

            socket?.on(Socket.EVENT_CONNECT_ERROR) { args ->
                val error = if (args.isNotEmpty()) args[0].toString() else "Unknown error"
                Log.e(TAG, "❌ 連接錯誤: $error")
                trySend(SocketEvent.Error(error))
            }

            // ✅ 監聽 "chat_message" 事件（後端發送的）
            socket?.on("chat_message") { args ->
                try {
                    Log.d(TAG, "📨 收到 chat_message")
                    val data = args[0] as JSONObject
                    Log.d(TAG, "📨 資料: ${data.toString()}")

                    val userId = data.getString("user_id")
                    val messageText = data.getString("message")

                    // 判斷是系統訊息還是用戶訊息
                    if (userId == "系統") {
                        Log.d(TAG, "📢 系統訊息: $messageText")
                        trySend(SocketEvent.SystemMessage(messageText))
                    } else {
                        // 一般聊天訊息
                        val message = ChatMessage(
                            id = System.currentTimeMillis().toString(),
                            content = messageText,
                            username = userId,  // 後端用 user_id 當作識別
                            userId = userId,
                            timestamp = System.currentTimeMillis()
                        )
                        Log.d(TAG, "✅ 用戶訊息: ${message.content}")
                        trySend(SocketEvent.NewMessage(message))
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "❌ 解析 chat_message 失敗", e)
                }
            }

            // ✅ 監聽 "trip" 事件（行程資料）
            socket?.on("trip") { args ->
                try {
                    Log.d(TAG, "🗺️ 收到 trip 資料")
                    val data = args[0] as JSONObject
                    val nodes = data.getJSONArray("nodes")
                    Log.d(TAG, "🗺️ 節點數量: ${nodes.length()}")
                    // 可以處理 trip 資料
                } catch (e: Exception) {
                    Log.e(TAG, "❌ 解析 trip 失敗", e)
                }
            }

            socket?.connect()

        } catch (e: Exception) {
            Log.e(TAG, "❌ 初始化失敗", e)
            trySend(SocketEvent.Error(e.message ?: "Unknown error"))
        }

        awaitClose {
            socket?.disconnect()
            socket?.off()
        }
    }

    // ✅ 發送 join 事件到後端
    fun joinRoom(tripId: String, username: String, userId: String) {
        Log.d(TAG, "👋 發送 join 事件: tripId=$tripId, userId=$userId, name=$username")
        val data = JSONObject().apply {
            put("trip_id", tripId)
            put("user_id", userId)
            put("name", username)
        }
        socket?.emit("join", data)  // ← 注意：事件名稱是 "join"
        Log.d(TAG, "✅ join 事件已發送")
    }

    fun leaveRoom(tripId: String, username: String) {
        // 如果後端有 leave 處理，可以加上
        socket?.emit("leave", JSONObject().apply {
            put("trip_id", tripId)
            put("user_id", username)
        })
    }

    // ✅ 發送聊天訊息
    fun sendMessage(tripId: String, message: String, username: String, userId: String) {
        Log.d(TAG, "📤 發送訊息: $message")
        val data = JSONObject().apply {
            put("trip_id", tripId)
            put("user_id", userId)
            put("message", message)
        }
        // 檢查你的後端是用什麼事件名稱接收聊天訊息
        // 如果是 "chat_message"，用這個：
        socket?.emit("chat_message", data)
        // 如果是其他名稱，請告訴我
    }

    fun sendTypingStatus(tripId: String, username: String, isTyping: Boolean) {
        // 如果需要的話可以實作
    }

    fun disconnect() {
        socket?.disconnect()
        socket?.off()
        socket = null
    }


}


// 訊息資料類
data class ChatMessage(
    val id: String,
    val content: String,
    val username: String,
    val userId: String,
    val timestamp: Long
)