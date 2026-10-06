package com.bragastudio.mobile.coremedia.di

import android.content.Context
import com.bragastudio.mobile.core.database.LutDao
import com.bragastudio.mobile.core.repository.LutRepository
import com.bragastudio.mobile.coremedia.persistence.LutRepositoryImpl
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object MediaModule {

    @Provides
    @Singleton
    fun provideLutRepository(
        lutDao: LutDao,
        @ApplicationContext context: Context,
    ): LutRepository = LutRepositoryImpl(lutDao, context)
}
