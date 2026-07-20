package com.bragastudio.mobile.corecapture.di

import com.bragastudio.mobile.corecapture.device.Camera2Device
import com.bragastudio.mobile.corecapture.domain.CaptureDevice
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class CaptureModule {

    @Binds
    @Singleton
    abstract fun bindCaptureDevice(
        camera2Device: Camera2Device
    ): CaptureDevice
}
