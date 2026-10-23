package dev.jdtech.jellyfin.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dev.jdtech.jellyfin.core.presentation.capability.ItemCapabilities
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {
    @Singleton
    @Provides
    fun provideItemCapabilities(): ItemCapabilities =
        object : ItemCapabilities {
            override val supportsBooks = false
        }
}
