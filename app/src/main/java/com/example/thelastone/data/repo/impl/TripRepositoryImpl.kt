package com.example.thelastone.data.repo.impl

import android.util.Log
import com.example.thelastone.data.model.Activity
import com.example.thelastone.data.model.Trip
import com.example.thelastone.data.model.TripForm
import com.example.thelastone.data.remote.TripApiService
import com.example.thelastone.data.repo.TripRepository
import com.example.thelastone.data.repo.TripStats
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import javax.inject.Inject
import javax.inject.Singleton

/**
 * TripRepository 的具體實作 (Implementation)。
 * @Inject constructor() 讓 Hilt 知道如何建立這個類別的實例。
 * @Singleton 確保在整個 App 中只有一個 TripRepositoryImpl 的實例。
 */
@Singleton
class TripRepositoryImpl @Inject constructor(
    // ✅ 2. 在這裡注入您的 API 服務，並取名為 tripApiService
    private val tripApiService: TripApiService
) : TripRepository {

    /**
     * 一個用於在記憶體中暫存表單的變數，用於在畫面之間傳遞資料。
     */
    private var formForPreview: TripForm? = null

    // ❗ 這是您原有的函式，請保留您自己的實作邏輯
    override suspend fun createTrip(form: TripForm): Trip {
        // 為了讓專案能編譯，我提供一個欄位完整的假實作
        return Trip(
            id = "preview_trip_id_${System.currentTimeMillis()}",
            createdBy = "preview_user",
            name = form.name,
            locations = form.locations,
            totalBudget = form.totalBudget,
            startDate = form.startDate,
            endDate = form.endDate,
            activityStart = form.activityStart,
            activityEnd = form.activityEnd,
            avgAge = form.avgAge,
            transportPreferences = form.transportPreferences,
            useGmapsRating = form.useGmapsRating,
            styles = form.styles,
            visibility = form.visibility,
            members = emptyList(),
            days = emptyList()
        )
    }

    // ❗ 這是您原有的函式，請保留您自己的實作邏輯
    override suspend fun saveTrip(trip: Trip): Trip {
        println("假裝正在儲存行程: ${trip.name}")
        return trip
    }

    // 🔽 [ [ [ 以下是為了讓專案能編譯而新增的假實作 ] ] ] 🔽
    // ❗ 請務必將這些函式的內容替換為您自己的真實後端邏輯

    override suspend fun getMyTrips(): List<Trip> {
        TODO("尚未實作 getMyTrips")
    }

    override fun observeMyTrips(): Flow<List<Trip>> {
        return flowOf(emptyList()) // 回傳一個空的 Flow
    }

    override suspend fun getPublicTrips(): List<Trip> {
        TODO("尚未實作 getPublicTrips")
    }

    override fun observePublicTrips(): Flow<List<Trip>> {
        return flowOf(emptyList())
    }

    override suspend fun getTripDetail(tripId: String): Trip {
        // ✅ 修正：換成您真正打 API 撈資料的程式碼
        return try {
            Log.d("TripRepo", "📡 正在透過 API 取得 tripId: $tripId 的詳細資料...")

            // ✅ 3. 使用您在建構子中定義的 'tripApiService'
            val tripData = tripApiService.getTripDetail(tripId)

            Log.d("TripRepo", "✅ 成功取得行程資料: ${tripData.name}")
            tripData // 成功時回傳 Trip 物件
        } catch (e: Exception) {
            Log.e("TripRepo", "❌ 取得行程 $tripId 詳細資料失敗", e)
            throw e // 發生錯誤時把例外拋出
        }
    }

    override fun observeTripDetail(tripId: String): Flow<Trip> {
        TODO("尚未實作 observeTripDetail")
    }

    override suspend fun addActivity(tripId: String, dayIndex: Int, activity: Activity) {
        TODO("尚未實作 addActivity")
    }

    override suspend fun updateActivity(tripId: String, dayIndex: Int, activityIndex: Int, updated: Activity) {
        TODO("尚未實作 updateActivity")
    }

    override suspend fun removeActivity(tripId: String, dayIndex: Int, activityIndex: Int) {
        TODO("尚未實作 removeActivity")
    }

    override suspend fun deleteTrip(tripId: String) {
        TODO("尚未實作 deleteTrip")
    }

    override suspend fun addMembers(tripId: String, userIds: List<String>) {
        TODO("尚未實作 addMembers")
    }

    override suspend fun getTripStatsFor(userId: String): TripStats {
        TODO("尚未實作 getTripStatsFor")
    }

    // --- 我們為 AI 行程生成新增的功能 ---
    override fun setTripFormForPreview(form: TripForm) {
        this.formForPreview = form
    }

    override fun getTripFormForPreview(): TripForm? {
        val form = this.formForPreview
        this.formForPreview = null
        return form
    }
}

