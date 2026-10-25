package com.mobyle.nanquim.document.color

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test

class CmykColorTest {
    private val halfGamut =
        object : CmykConversion {
            override fun inksOf(color: ColorValue): CmykInks {
                val black = 1f - maxOf(color.red, color.green, color.blue)
                val rest = 1f - black

                fun ink(channel: Float) = if (rest <= 0f) 0f else ((rest - channel) / rest).coerceAtMost(0.5f)
                return CmykInks(ink(color.red), ink(color.green), ink(color.blue), black)
            }

            override fun colorOf(
                inks: CmykInks,
                alpha: Float,
                previous: ColorValue?,
            ): ColorValue {
                val rest = 1f - inks.black
                return ColorValue.fromRgb(rest * (1 - inks.cyan), rest * (1 - inks.magenta), rest * (1 - inks.yellow), alpha, previous)
            }
        }

    @Test
    fun printableKeepsColorsInsideTheGamut() {
        val gray = ColorValue.fromRgb(0.4f, 0.4f, 0.4f)
        assertSame(gray, halfGamut.printable(gray))
    }

    @Test
    fun printableBringsColorsIntoTheGamut() {
        val red = ColorValue.fromRgb(1f, 0f, 0f, alpha = 0.5f)
        val printed = halfGamut.printable(red)
        assertEquals(1f, printed.red, 1e-5f)
        assertEquals(0.5f, printed.green, 1e-5f)
        assertEquals(0.5f, printed.blue, 1e-5f)
        assertEquals(0.5f, printed.alpha, 0f)
        assertEquals(0f, printed.hue, 1e-3f)
    }

    @Test
    fun inkChannelsReadAndWritePercent() {
        val inks = CmykInks(0.1f, 0.2f, 0.3f, 0.4f)
        assertEquals(listOf(10, 20, 30, 40), InkChannel.entries.map { it.percent(inks) })
        assertEquals(0.55f, InkChannel.Magenta.parse("55 %", inks)!!.magenta, 1e-6f)
        assertEquals(1f, InkChannel.Black.parse("250", inks)!!.black, 0f)
        assertEquals(0.125f, InkChannel.Cyan.parse("12,5", inks)!!.cyan, 1e-6f)
        assertNull(InkChannel.Yellow.parse("much", inks))
    }
}
