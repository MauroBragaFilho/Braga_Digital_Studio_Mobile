package com.bragastudio.mobile.corecapture

import com.serenegiant.usb.UVCParam
import org.junit.Test

class UVCParamTest {
    @Test
    fun testConstructors() {
        UVCParam::class.java.constructors.forEach {
            println("Constructor: " + it)
        }
    }
}
