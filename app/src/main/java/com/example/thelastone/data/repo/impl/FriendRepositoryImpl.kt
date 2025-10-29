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
}