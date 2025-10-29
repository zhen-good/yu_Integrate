package com.example.thelastone.data.remote

import com.example.thelastone.data.model.User
import retrofit2.http.GET
import retrofit2.http.Path

interface FriendApi {
    @GET("/friends/{userId}")  // ← 改成 {userId}
    suspend fun getFriends(
        @Path("userId") userId: String
    ): List<User>
}