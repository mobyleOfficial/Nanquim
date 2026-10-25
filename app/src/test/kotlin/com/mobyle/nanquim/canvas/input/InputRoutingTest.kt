package com.mobyle.nanquim.canvas.input

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class InputRoutingTest {
    @Test
    fun `stylus and mouse use the current tool`() {
        assertEquals(PointerRoute.Paint, routePointer(PointerTool.Stylus, CanvasTool.Brush))
        assertEquals(PointerRoute.Erase, routePointer(PointerTool.Stylus, CanvasTool.Eraser))
        assertEquals(PointerRoute.Paint, routePointer(PointerTool.Mouse, CanvasTool.Brush))
        assertEquals(PointerRoute.Erase, routePointer(PointerTool.Mouse, CanvasTool.Eraser))
    }

    @Test
    fun `adjustments take the stylus only in Pencil mode`() {
        assertEquals(PointerRoute.Ignore, routePointer(PointerTool.Stylus, CanvasTool.Adjust))
        assertEquals(PointerRoute.Paint, routePointer(PointerTool.Stylus, CanvasTool.Adjust, adjustmentPencil = true))
        assertEquals(PointerRoute.Erase, routePointer(PointerTool.StylusEraser, CanvasTool.Adjust, adjustmentPencil = true))
        assertEquals(PointerRoute.Navigate, routePointer(PointerTool.Finger, CanvasTool.Adjust, adjustmentPencil = true))
    }

    @Test
    fun `eraser end always erases and fingers navigate`() {
        assertEquals(PointerRoute.Erase, routePointer(PointerTool.StylusEraser, CanvasTool.Brush))
        assertEquals(PointerRoute.Navigate, routePointer(PointerTool.Finger, CanvasTool.Brush))
        assertEquals(PointerRoute.Ignore, routePointer(PointerTool.Unknown, CanvasTool.Brush))
    }

    @Test
    fun `editing the guide paints nothing and fingers still navigate`() {
        assertEquals(PointerRoute.Ignore, routePointer(PointerTool.Stylus, CanvasTool.Guide))
        assertEquals(PointerRoute.Ignore, routePointer(PointerTool.Mouse, CanvasTool.Guide))
        assertEquals(PointerRoute.Ignore, routePointer(PointerTool.StylusEraser, CanvasTool.Guide))
        assertEquals(PointerRoute.Navigate, routePointer(PointerTool.Finger, CanvasTool.Guide))
    }

    @Test
    fun `tool types map to pointer tools`() {
        assertEquals(PointerTool.Finger, PointerTool.fromToolType(1))
        assertEquals(PointerTool.Stylus, PointerTool.fromToolType(2))
        assertEquals(PointerTool.Mouse, PointerTool.fromToolType(3))
        assertEquals(PointerTool.StylusEraser, PointerTool.fromToolType(4))
        assertEquals(PointerTool.Unknown, PointerTool.fromToolType(0))
    }

    @Test
    fun `fingers are rejected while the stylus hovers or touches and during the grace period`() {
        val palm = PalmRejection(graceMillis = 300)
        assertTrue(palm.fingersAllowed(0))

        palm.onStylusHover(hovering = true, timeMillis = 1000)
        assertFalse(palm.fingersAllowed(1100))
        palm.onStylusTouch(touching = true, timeMillis = 1200)
        palm.onStylusHover(hovering = false, timeMillis = 1200)
        assertFalse(palm.fingersAllowed(5000))

        palm.onStylusTouch(touching = false, timeMillis = 6000)
        assertFalse(palm.fingersAllowed(6299))
        assertTrue(palm.fingersAllowed(6300))
    }
}
