package com.example.thelastone.data.repo

import com.example.thelastone.data.model.User


//這是friend的實作
interface FriendRepository {
    suspend fun getFriends(userId: String): List<User>

    suspend fun searchUsers(keyword: String): List<User>
}