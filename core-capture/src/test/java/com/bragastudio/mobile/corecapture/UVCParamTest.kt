package com.bragastudio.mobile.corecapture

import org.junit.Test
import com.serenegiant.usb.UVCParam

class UVCParamTest {
    @Test
    fun testConstructors() {
        UVCParam::class.java.constructors.forEach {
            println("Constructor: " + it)
        }
    }
}
