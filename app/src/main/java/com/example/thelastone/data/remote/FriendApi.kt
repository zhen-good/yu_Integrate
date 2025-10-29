package com.example.thelastone.data.remote

import com.example.thelastone.data.model.User
import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Query

interface FriendApi {
    @GET("/friends/{userId}")  // ← 改成 {userId}
    suspend fun getFriends(
        @Path("userId") userId: String
    ): List<User>

    @GET("/users/search")
    suspend fun searchUsers(
        @Query("keyword") keyword: String // 將 keyword 放在 URL 查詢參數中
    ): List<User>
}