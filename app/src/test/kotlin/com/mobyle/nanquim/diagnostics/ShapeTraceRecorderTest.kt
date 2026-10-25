package com.mobyle.nanquim.diagnostics

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import com.mobyle.nanquim.document.shape.ShapeTraceSet
import java.io.File

class ShapeTraceRecorderTest {
    @TempDir
    lateinit var folder: File

    private val file get() = File(folder, "diagnostics/shape-traces.json")

    private fun recorder() = ShapeTraceRecorder(file, "Test tablet", CoroutineScope(Dispatchers.Unconfined), Dispatchers.Unconfined)

    @Test
    fun tracesAreRoundedSavedAndLoadedAgain() =
        runBlocking {
            val recorder = recorder()
            recorder.load()
            recorder.add("line", "  Ana ", listOf(doubleArrayOf(10.123, 20.987, 0.12345, 0.0), doubleArrayOf(30.0, 40.0, 0.5, 8.4)))
            recorder.add("ellipse", "Ana", listOf(doubleArrayOf(1.0, 2.0, 1.0, 0.0)))
            val saved = ShapeTraceSet.decode(file.readText())
            assertEquals("Test tablet", saved.device)
            assertEquals(listOf(listOf(10.1, 21.0, 0.123, 0.0), listOf(30.0, 40.0, 0.5, 8.0)), saved.traces.first().points)
            assertEquals("Ana", saved.traces.first().recordedBy)
            val reloaded = recorder()
            reloaded.load()
            assertEquals(saved.traces, reloaded.traces)
            assertEquals(1, reloaded.counts()["line"])
            assertEquals(0, reloaded.counts()["arc"])
        }

    @Test
    fun removeLastClearAndUnknownLabels() =
        runBlocking {
            val recorder = recorder()
            recorder.load()
            assertFalse(recorder.removeLast())
            recorder.add("arc", "", listOf(doubleArrayOf(0.0, 0.0, 1.0, 0.0)))
            recorder.add("curve", "", listOf(doubleArrayOf(0.0, 0.0, 1.0, 0.0)))
            assertTrue(recorder.removeLast())
            assertEquals(listOf("arc"), recorder.traces.map { it.label })
            recorder.clear()
            assertTrue(ShapeTraceSet.decode(file.readText()).traces.isEmpty())
            assertThrows<IllegalArgumentException> { recorder.add("circle", "", emptyList()) }
            Unit
        }

    @Test
    fun anUnreadableFileIsSetAside() =
        runBlocking {
            file.parentFile.mkdirs()
            file.writeText("{ not json")
            val recorder = recorder()
            recorder.load()
            assertTrue(recorder.traces.isEmpty())
            assertTrue(File(file.parentFile, "shape-traces.json.unreadable").isFile)
        }
}
