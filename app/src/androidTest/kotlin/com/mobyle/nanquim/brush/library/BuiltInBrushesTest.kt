package com.mobyle.nanquim.brush.library

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import com.mobyle.nanquim.engine.Engine
import com.mobyle.nanquim.engine.EngineConfig
import com.mobyle.nanquim.engine.StrokeTool
import java.io.File

@RunWith(AndroidJUnit4::class)
class BuiltInBrushesTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var engine: Engine

    @Before
    fun createEngine() {
        val scratch = File(context.cacheDir, "built-in-brushes-test").apply { mkdirs() }
        engine = Engine(EngineConfig(memoryBudgetBytes = 256L * 1024 * 1024, scratchDirectory = scratch.absolutePath))
    }

    @After
    fun closeEngine() = InstrumentationRegistry.getInstrumentation().runOnMainSync { engine.close() }

    @Test
    fun everyBuiltInBrushLoads() {
        val source = AssetBrushSource(context.assets)
        val library = source.library()
        assertTrue("no built-in brushes", library.groups.isNotEmpty())
        val ids = mutableSetOf<String>()
        for (group in library.groups) {
            for (brushId in group.brushes) {
                val brushPackage = source.readPackage(source.pathOf(group.key, brushId))
                assertEquals("id of ${group.key}/$brushId", brushId, brushPackage.document.id)
                assertTrue("duplicate id $brushId", ids.add(brushId))
                val error =
                    engine.setBrush(
                        StrokeTool.Paint,
                        brushPackage.document.toJsonText(),
                        brushPackage.shapeTextureBytes,
                        brushPackage.grainTextureBytes,
                    )
                assertNull("${brushPackage.document.name}: $error", error)
            }
        }
        for ((tool, brushId) in library.defaults) {
            assertTrue("default $tool brush $brushId is not in the library", brushId in ids)
        }
    }
}
