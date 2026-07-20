package com.bragastudio.mobile.core.database

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object CoreDatabaseModule {

    @Provides
    @Singleton
    fun provideBsmDatabase(@ApplicationContext context: Context): BsmDatabase {
        return BsmDatabase.getInstance(context)
    }

    @Provides
    @Singleton
    fun provideLutDao(database: BsmDatabase): LutDao {
        return database.lutDao()
    }

    @Provides
    @Singleton
    fun provideRecordingDao(database: BsmDatabase): RecordingDao {
        return database.recordingDao()
    }
}
