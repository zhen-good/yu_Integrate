package com.example.thelastone.data.remote

import android.util.Log
import io.socket.client.Socket
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ChatWebSocketService @Inject constructor(
    private val socket: Socket  // ✅ 注入同一個 Socket
) {
    companion object {
        private const val TAG = "ChatWebSocket"
    }

    init {
        Log.d(TAG, "🏭 ChatWebSocketService 初始化")
        Log.d(TAG, "🏭 Socket 實例 ID: ${System.identityHashCode(socket)}")
    }

    fun connect(): Flow<SocketEvent> = callbackFlow {
        try {
            Log.d(TAG, "🚀 設定 Socket 監聽器")
            Log.d(TAG, "🔌 Socket 連線狀態: ${socket.connected()}")

            socket.on(Socket.EVENT_CONNECT) {
                Log.d(TAG, "✅ Socket 已連線")
                trySend(SocketEvent.Connected)
            }

            socket.on(Socket.EVENT_DISCONNECT) {
                Log.d(TAG, "❌ Socket 已斷線")
                trySend(SocketEvent.Disconnected)
            }

            socket.on(Socket.EVENT_CONNECT_ERROR) { args ->
                val error = if (args.isNotEmpty()) args[0].toString() else "Unknown error"
                Log.e(TAG, "❌ 連線錯誤: $error")
                trySend(SocketEvent.Error(error))
            }


            // 監聽 chat_message 事件
            socket.on("chat_message") { args ->
                try {
                    Log.d(TAG, "📨 收到 chat_message")
                    val data = args[0] as JSONObject
                    Log.d(TAG, "📨 資料: $data")

                    val userId = data.getString("user_id")
                    val messageText = data.getString("message")

                    if (userId == "系統") {
                        Log.d(TAG, "📢 系統訊息: $messageText")
                        trySend(SocketEvent.SystemMessage(messageText))
                    } else {
                        val message = ChatMessage(
                            id = System.currentTimeMillis().toString(),
                            content = messageText,
                            username = userId,
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

            socket.on("ai_question_v2") { args ->
                val raw = args.firstOrNull()?.toString() ?: return@on
                Log.d(TAG, "🧩 收到 ai_question_v2: $raw")
                trySend(SocketEvent.AiQuestionV2(raw))
            }


            // 監聽 trip 事件
            socket.on("trip") { args ->
                try {
                    Log.d(TAG, "🗺️ 收到 trip 資料")
                    val data = args[0] as JSONObject
                    val nodes = data.getJSONArray("nodes")
                    Log.d(TAG, "🗺️ 節點數量: ${nodes.length()}")
                } catch (e: Exception) {
                    Log.e(TAG, "❌ 解析 trip 失敗", e)
                }
            }

            // ✅ 如果還沒連線，開始連線
            if (!socket.connected()) {
                Log.d(TAG, "開始連線...")
                socket.connect()
            } else {
                Log.d(TAG, "Socket 已連線，直接發送 Connected 事件")
                trySend(SocketEvent.Connected)
            }

        } catch (e: Exception) {
            Log.e(TAG, "❌ 初始化失敗", e)
            trySend(SocketEvent.Error(e.message ?: "Unknown error"))
        }


        awaitClose {
            Log.d(TAG, "⚠️ Flow 關閉，移除監聽器")
            socket.off(Socket.EVENT_CONNECT)
            socket.off(Socket.EVENT_DISCONNECT)
            socket.off(Socket.EVENT_CONNECT_ERROR)
            socket.off("chat_message")
            socket.off("trip")
        }
    }


    fun joinRoom(tripId: String, username: String, userId: String) {
        Log.d(TAG, "👋 發送 join 事件")
        Log.d(TAG, "  tripId: $tripId")
        Log.d(TAG, "  userId: $userId")
        Log.d(TAG, "  name: $username")
        Log.d(TAG, "  Socket 連線: ${socket.connected()}")

        val data = JSONObject().apply {
            put("trip_id", tripId)
            put("user_id", userId)
            put("name", username)
        }

        socket.emit("join", data)
        Log.d(TAG, "✅ join 事件已發送")
    }

    fun leaveRoom(tripId: String, username: String) {
        socket.emit("leave", JSONObject().apply {
            put("trip_id", tripId)
            put("user_id", username)
        })
    }

    fun sendMessage(tripId: String, userId: String, message: String) {
        Log.d(TAG, "📤 發送訊息: $message")
        Log.d(TAG, "  Socket 連線: ${socket.connected()}")

        if (!socket.connected()) {
            Log.e(TAG, "❌ Socket 未連線，無法發送")
            return
        }

        val data = JSONObject().apply {
            put("trip_id", tripId)
            put("user_id", userId)
            put("message", message)
        }

        socket.emit("user_message", data)
        Log.d(TAG, "✅ user_message 事件已發送")
    }

    fun sendTypingStatus(tripId: String, username: String, isTyping: Boolean) {
        // 實作打字狀態
    }

    fun disconnect() {
        socket.disconnect()
    }
}

data class ChatMessage(
    val id: String,
    val content: String,
    val username: String,
    val userId: String,
    val timestamp: Long
)