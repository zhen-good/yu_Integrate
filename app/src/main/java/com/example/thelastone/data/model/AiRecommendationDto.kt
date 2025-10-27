package com.example.thelastone.data.model

import kotlinx.serialization.Serializable

// 用於描述 AI 推薦的單一地點 (替代選項或新增地點)
// 💡 注意：這裡使用與您現有的 TripNodePlaceDto 相似的結構，但欄位可能需要簡化
@Serializable
data class RecommendedPlaceDto(
    // 關鍵資訊：地點 ID 和名稱，以便前端點擊後進行操作
    val place_id: String,
    val name: String,
    // 如果後端有傳送，可以加上經緯度
    val lat: Double? = null,
    val lng: Double? = null,
    // 根據您的後端日誌，後端傳送的只是地點名稱，如果後端沒有傳送 place_id，則前端無法操作。
    // 因此，建議後端傳送更完整的地點 DTO。
)


// 描述單個 AI 建議的結構 (對應後端發送的 "recommendation" 字段)
@Serializable
data class AiRecommendationData(
    val type: String,   // 'modify', 'add', 'delete'
    val day: Int? = null, // 建議修改或新增是哪一天
    val place: String?, // 原景點名稱 (for modify/delete) 或 新增景點名稱 (for add)
    val reason: String, // 替換原因

    // 僅 modify 需要：驗證過後的 3 個替代地點
    // 💡 根據您的後端日誌，您的後端傳送的是地點名稱列表，因此這裡暫時匹配 List<String>
    // 📌 最佳實踐：如果您的後端已經做了 Google Maps 驗證，應該傳送包含 place_id 的 List<RecommendedPlaceDto>
    // 假設後端傳送 List<String> (地點名稱)：
    val new_places: List<String> = emptyList()
)


// 匹配後端 emit("ai_response", ...) 發送的最外層 JSON 負載
@Serializable
data class AiResponsePayload(
    val message: String, // AI 的文字提示 (例如："請回覆想選擇的編號...")
    val recommendation: AiRecommendationData? = null // 結構化的建議數據
)

//--------------------------------------------------------------