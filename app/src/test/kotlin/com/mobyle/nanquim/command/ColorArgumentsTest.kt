package com.mobyle.nanquim.command

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import com.mobyle.nanquim.document.color.ColorValue

class ColorArgumentsTest {
    private val previous = ColorValue.fromHsb(200f, 0.5f, 0.5f, alpha = 0.6f)

    private fun parse(arguments: String) = Json.parseToJsonElement(arguments).jsonObject.colorValue(previous)

    @Test
    fun `rgb keeps the previous alpha and the hue of a gray`() {
        val gray = parse("""{"red": 0.5, "green": 0.5, "blue": 0.5, "hex": null}""")!!
        assertEquals(0.6f, gray.alpha)
        assertEquals(200f, gray.hue, 1e-3f)
    }

    @Test
    fun `hsb and hex describe the same color`() {
        val hsb = parse("""{"hue": 0, "saturation": 1, "brightness": 1, "alpha": 1}""")!!
        val hex = parse("""{"hex": "#FF0000", "alpha": 1}""")!!
        assertEquals(hsb, hex)
        assertEquals("#FF0000", hsb.toHex())
        assertEquals(0.6f, parse("""{"hex": "ff0000"}""")!!.alpha)
    }

    @Test
    fun `exactly one complete form is required`() {
        assertNull(parse("""{}"""))
        assertNull(parse("""{"red": 1, "green": 0}"""))
        assertNull(parse("""{"red": 1, "green": 0, "blue": 0, "hex": "FF0000"}"""))
        assertNull(parse("""{"hue": 10, "saturation": 1, "brightness": 1, "red": 1}"""))
        assertNull(parse("""{"hex": "red"}"""))
        assertNull(parse("""{"alpha": 0.5}"""))
    }
}
