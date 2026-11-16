package com.mobyle.nanquim.engine

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Test
import java.io.File

/**
 * Starts the GLES engine on an empty shader cache, so every program initializeRenderers needs is
 * compiled from source, the way a fresh install does it. Meant for devices whose GLES compiler
 * crashes the process: the "Compiling shader" lines in logcat name the program it died on.
 */
class ShaderCompileSmokeTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun glesProgramsCompileFromAnEmptyCache() {
        val scratch = File(context.cacheDir, "shader-smoke-test")
        scratch.deleteRecursively()
        scratch.mkdirs()
        val engine =
            Engine(
                EngineConfig(
                    memoryBudgetBytes = 256L * 1024 * 1024,
                    scratchDirectory = scratch.absolutePath,
                    renderer = Renderer.Gles,
                ),
            )
        try {
            engine.checkRenderer(Renderer.Gles)
        } finally {
            InstrumentationRegistry.getInstrumentation().runOnMainSync { engine.close() }
        }
    }
}
