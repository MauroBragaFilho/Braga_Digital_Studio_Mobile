package com.bragastudio.mobile.corecapture.di

import android.content.Context
import com.bragastudio.mobile.core.database.RecordingDao
import com.bragastudio.mobile.core.repository.RecordingRepository
import com.bragastudio.mobile.corecapture.data.RecordingRepositoryImpl
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object CaptureDataModule {

    @Provides
    @Singleton
    fun provideRecordingRepository(
        recordingDao: RecordingDao,
        @ApplicationContext context: Context
    ): RecordingRepository {
        return RecordingRepositoryImpl(recordingDao, context)
    }
}
