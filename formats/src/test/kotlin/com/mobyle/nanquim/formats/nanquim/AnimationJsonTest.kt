package com.mobyle.nanquim.formats.nanquim

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import com.mobyle.nanquim.document.animation.AnimationSettings
import com.mobyle.nanquim.document.animation.OnionBlend
import com.mobyle.nanquim.document.animation.OnionSkin
import com.mobyle.nanquim.document.animation.PlaybackMode
import com.mobyle.nanquim.document.layer.RgbaColor

class AnimationJsonTest {
    private val uuids = mapOf(1 to "a1", 2 to "b2")

    @Test
    fun roundTripsEverySettingByNodeUuid() {
        val settings =
            AnimationSettings(
                enabled = true,
                playback = PlaybackMode.PingPong,
                framesPerSecond = 24,
                onionSkin = OnionSkin(frames = 3, opacity = 0.4f, colorPrevious = RgbaColor(1f, 0f, 0f, 1f), blend = OnionBlend.Normal, enabled = false),
                firstFrameIsBackground = true,
                holdFrames = mapOf(2 to 5),
                extra = JsonObject(mapOf("future" to JsonPrimitive(7))),
            )
        val bytes = AnimationJson.write(settings, uuids::get)
        val text = String(bytes)
        val read = AnimationJson.read(text) { uuid -> uuids.entries.firstOrNull { it.value == uuid }?.key }
        assertEquals(settings, read)
        assertEquals("""{"b2":{"holdFrames":5}}""", (Json.parseToJsonElement(text) as JsonObject)["frames"].toString())
    }

    @Test
    fun brokenOrHostileFilesReadAsAnimationOff() {
        assertFalse(AnimationJson.read("not json") { null }.enabled)
        val read = AnimationJson.read("""{"enabled": true, "framesPerSecond": 9999, "frames": {"zz": {"holdFrames": 3}}}""") { null }
        assertEquals(60, read.framesPerSecond)
        assertEquals(emptyMap<Int, Int>(), read.holdFrames)
    }
}
