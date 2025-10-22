package com.example.thelastone.di

import com.example.thelastone.data.repo.impl.fake.FakeChatService
import com.example.thelastone.data.remote.ChatService
import com.example.thelastone.data.repo.ChatRepository
import com.example.thelastone.data.repo.impl.ChatRepositoryImpl
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

// di/ChatModule.kt
@Module
@InstallIn(SingletonComponent::class)
object ChatModule {

    @Provides @Singleton
    fun provideChatRepository(
        impl: ChatRepositoryImpl
    ): ChatRepository = impl

    @Provides @Singleton
    fun provideChatService(
        fake: FakeChatService
    ): ChatService = fake
}
