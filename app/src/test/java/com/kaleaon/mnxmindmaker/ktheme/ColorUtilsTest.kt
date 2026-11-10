package com.kaleaon.mnxmindmaker.ktheme

import android.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ColorUtilsTest {

    @Test
    fun testHexToColorInt_valid6CharWithHash() {
        val colorInt = ColorUtils.hexToColorInt("#FF0000")
        assertEquals(Color.RED, colorInt)
    }

    @Test
    fun testHexToColorInt_valid6CharWithoutHash() {
        val colorInt = ColorUtils.hexToColorInt("00FF00")
        assertEquals(Color.GREEN, colorInt)
    }

    @Test
    fun testHexToColorInt_valid8CharArgbWithHash() {
        val colorInt = ColorUtils.hexToColorInt("#800000FF")
        assertEquals(0x800000FF.toInt(), colorInt)
    }

    @Test
    fun testHexToColorInt_valid8CharArgbWithoutHash() {
        val colorInt = ColorUtils.hexToColorInt("800000FF")
        assertEquals(0x800000FF.toInt(), colorInt)
    }

    @Test
    fun testHexToColorInt_invalidHex() {
        assertThrows(IllegalArgumentException::class.java) {
            ColorUtils.hexToColorInt("invalid_hex")
        }
    }

    @Test
    fun testColorIntToHex() {
        val hex = ColorUtils.colorIntToHex(Color.RED)
        assertEquals("#FF0000", hex)
    }

    @Test
    fun testColorIntToHex_withAlpha() {
        val colorInt = 0x80123456.toInt()
        val hex = ColorUtils.colorIntToHex(colorInt)
        assertEquals("#123456", hex)
    }

    @Test
    fun testRoundTripConversion() {
        val originalHex = "#123456"
        val colorInt = ColorUtils.hexToColorInt(originalHex)
        val formattedHex = ColorUtils.colorIntToHex(colorInt)
        assertEquals(originalHex, formattedHex)
    }
}
