package com.mobyle.nanquim.engine

import android.content.Intent
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class VulkanProbeTest {
    @Test
    fun probeVulkanOnAWindow() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val intent =
            Intent(context, ProbeSurfaceActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val activity = instrumentation.startActivitySync(intent) as ProbeSurfaceActivity
        try {
            val surface = activity.awaitSurface(10)
            assertNotNull("The probe window has no surface", surface)
            val report = VulkanProbe.run(surface!!, switchCycles = 10)
            report.chunked(3000).forEach { Log.i("VulkanProbe", it) }
            File(context.getExternalFilesDir(null), "vulkan-probe.json").writeText(report)
            val vulkan =
                Json
                    .parseToJsonElement(report)
                    .jsonObject
                    .getValue("vulkan")
                    .jsonObject
            assertFalse("Vulkan probe failed: ${vulkan["error"]}", vulkan.containsKey("error"))
        } finally {
            activity.finish()
        }
    }
}
