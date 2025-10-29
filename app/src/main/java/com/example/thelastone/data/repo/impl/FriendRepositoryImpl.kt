package com.example.thelastone.data.repo.impl

import com.example.thelastone.data.remote.FriendApi
import com.example.thelastone.data.repo.FriendRepository
import com.example.thelastone.data.model.User


class FriendRepositoryImpl(
    private val api: FriendApi
) : FriendRepository {

    override suspend fun getFriends(userId: String): List<User> {
        return api.getFriends(userId = userId)
    }

    // Data Repository 實作 (使用傳入的 keyword 參數)
    override suspend fun searchUsers(keyword: String): List<User> {
        // ✅ 正確：將傳入的 keyword 參數傳遞給 API 服務
        return api.searchUsers(keyword)
    }
}

