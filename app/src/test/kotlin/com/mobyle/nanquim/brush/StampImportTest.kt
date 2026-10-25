package com.mobyle.nanquim.brush

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import com.mobyle.nanquim.brush.library.BrushLibrary

class StampImportTest {
    private val transparent = 0
    private val opaqueWhite = 0xFFFFFFFF.toInt()
    private val halfRed = 0x80FF0000.toInt()
    private val opaqueBlack = 0xFF000000.toInt()

    @Test
    fun `coverage follows the mode`() {
        assertEquals(255, StampImport.coverage(opaqueWhite, StampMode.Luminance))
        assertEquals(0, StampImport.coverage(opaqueBlack, StampMode.Luminance))
        assertEquals(255, StampImport.coverage(opaqueBlack, StampMode.Alpha))
        assertEquals(128, StampImport.coverage(halfRed, StampMode.Color))
        assertEquals(38, StampImport.coverage(halfRed, StampMode.Luminance))
    }

    @Test
    fun `the tip is cropped to what paints and centered on a square`() {
        val pixels = IntArray(20) { transparent }
        for (x in 1..3) pixels[2 * 5 + x] = opaqueWhite
        val tip = StampImport.squareTip(pixels, 5, 4, StampMode.Alpha)!!
        assertEquals(3, tip.side)
        val white = opaqueWhite
        val none = opaqueBlack
        assertArrayEquals(intArrayOf(none, none, none, white, white, white, none, none, none), tip.pixels)

        val color = StampImport.squareTip(pixels, 5, 4, StampMode.Color)!!
        assertEquals(transparent, color.pixels[0])
        assertEquals(opaqueWhite, color.pixels[4])
    }

    @Test
    fun `an image that paints nothing gives no tip`() {
        assertNull(StampImport.squareTip(IntArray(4) { transparent }, 2, 2, StampMode.Alpha))
        assertNull(StampImport.squareTip(IntArray(4) { opaqueBlack }, 2, 2, StampMode.Luminance))
    }

    @Test
    fun `the new brush is a large stamp with the tip`() {
        val brush = StampImport.brush("Leaf", byteArrayOf(1, 2, 3), StampMode.Color)
        assertEquals("Leaf", brush.document.name)
        assertEquals(true, brush.document.boolean("stamp.enabled"))
        assertEquals(true, brush.document.boolean("stamp.keepColors"))
        assertEquals(true, brush.document.boolean("properties.stampPreview"))
        assertArrayEquals(byteArrayOf(1, 2, 3), brush.shapeTextureBytes)
        assertTrue(BrushLibrary.isSafeId(brush.document.id))
        assertEquals(false, StampImport.brush("Gray", byteArrayOf(1), StampMode.Alpha).document.boolean("stamp.keepColors"))
    }
}
