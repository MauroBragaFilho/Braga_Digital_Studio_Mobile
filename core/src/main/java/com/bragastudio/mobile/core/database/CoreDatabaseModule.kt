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

    // getInstance registra BdsmDatabase.ALL_MIGRATIONS (1->2, 2->3, 3->4) e o callback que
    // reconcilia takes IN_PROGRESS órfãos na abertura; sem fallback destrutivo.
    @Provides
    @Singleton
    fun provideBdsmDatabase(@ApplicationContext context: Context): BdsmDatabase = BdsmDatabase.getInstance(context)

    @Provides
    @Singleton
    fun provideLutDao(database: BdsmDatabase): LutDao = database.lutDao()

    @Provides
    @Singleton
    fun provideRecordingDao(database: BdsmDatabase): RecordingDao = database.recordingDao()
}
