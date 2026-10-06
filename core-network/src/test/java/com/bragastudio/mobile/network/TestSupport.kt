package com.bragastudio.mobile.network

import android.util.Log
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkStatic

/** android.util.Log não existe em testes JVM puros: silencia as chamadas usadas pelo código de produção. */
internal fun mockAndroidLog() {
    mockkStatic(Log::class)
    every { Log.i(any<String>(), any<String>()) } returns 0
    every { Log.d(any<String>(), any<String>()) } returns 0
    every { Log.w(any<String>(), any<String>()) } returns 0
    every { Log.e(any<String>(), any<String>()) } returns 0
}

internal fun unmockAndroidLog() {
    unmockkStatic(Log::class)
}
