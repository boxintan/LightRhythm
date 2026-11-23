package com.example.lightrhythm.repair

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ArgbPlanesTest {

    @Test
    fun splitsChannels() {
        val pixels = intArrayOf(0xFF336699.toInt(), 0xFFFFFFFF.toInt(), 0xFF000000.toInt())

        val planes = ArgbPlanes.toPlanes(pixels)

        assertEquals(0x33 / 255f, planes[0][0], 1e-6f)
        assertEquals(0x66 / 255f, planes[1][0], 1e-6f)
        assertEquals(0x99 / 255f, planes[2][0], 1e-6f)
        assertEquals(1f, planes[0][1], 1e-6f)
        assertEquals(0f, planes[2][2], 1e-6f)
    }

    @Test
    fun roundTripsExactly() {
        val pixels = intArrayOf(
            0xFF336699.toInt(),
            0x80FF0080.toInt(),
            0x00000000,
            0xFFFFFF00.toInt(),
        )

        val restored = ArgbPlanes.fromPlanes(ArgbPlanes.toPlanes(pixels), pixels)

        assertEquals(pixels.toList(), restored.toList())
    }

    @Test
    fun keepsOriginalAlpha() {
        val original = intArrayOf(0x40112233)
        val planes = arrayOf(floatArrayOf(1f), floatArrayOf(0f), floatArrayOf(0.5f))

        val packed = ArgbPlanes.fromPlanes(planes, original)

        assertEquals(0x40, (packed[0] ushr 24) and 0xFF)
        assertEquals(255, (packed[0] ushr 16) and 0xFF)
        assertEquals(0, (packed[0] ushr 8) and 0xFF)
        assertEquals(128, packed[0] and 0xFF)
    }

    @Test
    fun clampsOutOfRangeValues() {
        val original = intArrayOf(0xFF000000.toInt())
        val planes = arrayOf(floatArrayOf(1.8f), floatArrayOf(-0.4f), floatArrayOf(0.5f))

        val packed = ArgbPlanes.fromPlanes(planes, original)

        assertEquals(255, (packed[0] ushr 16) and 0xFF)
        assertEquals(0, (packed[0] ushr 8) and 0xFF)
    }

    @Test
    fun doesNotMutateInputs() {
        val pixels = intArrayOf(0xFF336699.toInt())
        val originalSnapshot = pixels.copyOf()
        val planes = ArgbPlanes.toPlanes(pixels)
        val planeSnapshot = planes.map { it.copyOf() }

        val packed = ArgbPlanes.fromPlanes(planes, pixels)

        assertEquals(originalSnapshot.toList(), pixels.toList())
        for (c in 0 until 3) {
            assertEquals(planeSnapshot[c].toList(), planes[c].toList())
        }
        assertFalse("must return a new array", packed === pixels)
    }
}
