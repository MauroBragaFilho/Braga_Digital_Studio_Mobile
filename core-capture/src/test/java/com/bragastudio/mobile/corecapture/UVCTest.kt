package com.bragastudio.mobile.corecapture

import org.junit.Test
import com.serenegiant.usb.UVCCamera

class UVCTest {
    @Test
    fun testConstructors() {
        UVCCamera::class.java.constructors.forEach {
            println("Constructor: " + it)
        }
    }
}
