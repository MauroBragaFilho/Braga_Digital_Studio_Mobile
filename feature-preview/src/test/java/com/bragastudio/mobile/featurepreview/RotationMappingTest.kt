package com.bragastudio.mobile.featurepreview

import org.junit.Assert.assertEquals
import org.junit.Test

class RotationMappingTest {
    // Valores de android.view.Surface.ROTATION_*: 0, 1, 2, 3.
    @Test fun rotacao0() = assertEquals(0f, rotationDegreesFor(0), 0f)

    @Test fun rotacao90InverteParaTresentosESetenta() = assertEquals(270f, rotationDegreesFor(1), 0f)

    @Test fun rotacao180() = assertEquals(180f, rotationDegreesFor(2), 0f)

    @Test fun rotacao270InverteParaNoventa() = assertEquals(90f, rotationDegreesFor(3), 0f)

    @Test fun valorDesconhecidoCaiEmZero() = assertEquals(0f, rotationDegreesFor(42), 0f)
}
