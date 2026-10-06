package com.bragastudio.mobile.corecapture

import com.serenegiant.usb.UVCCamera
import org.junit.Test

class UVCTest {
    @Test
    fun testConstructors() {
        UVCCamera::class.java.constructors.forEach {
            println("Constructor: " + it)
        }
    }
}
