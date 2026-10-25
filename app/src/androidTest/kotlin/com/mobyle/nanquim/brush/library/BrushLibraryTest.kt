package com.mobyle.nanquim.brush.library

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import com.mobyle.nanquim.document.brush.BrushDocument
import com.mobyle.nanquim.document.brush.BrushPackage
import java.io.File

@RunWith(AndroidJUnit4::class)
class BrushLibraryTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var directory: File
    private lateinit var source: FakeSource
    private val openDatabases = mutableListOf<BrushLibraryDatabase>()

    private class FakeSource(
        var library: BuiltInLibrary,
        val packages: MutableMap<String, BrushPackage>,
    ) : BuiltInBrushSource {
        override fun library() = library

        override fun readPackage(path: String) = packages.getValue(path)
    }

    private fun brush(
        id: String,
        name: String,
        spacing: Double = 0.1,
    ) = BrushPackage(
        BrushDocument.parse("""{ "format": 1, "id": "$id", "name": "$name", "path": { "spacing": $spacing }, "defaults": { "size": 0.5 } }"""),
        mapOf("shape.png" to byteArrayOf(1, 2, 3)),
    )

    @Before
    fun setUp() {
        directory = File(context.cacheDir, "brush-library-test").apply { deleteRecursively() }
        source =
            FakeSource(
                BuiltInLibrary(
                    format = 1,
                    version = 1,
                    defaults = mapOf("paint" to "pen", "erase" to "air"),
                    groups = listOf(BuiltInGroup("inking", listOf("pen", "marker")), BuiltInGroup("airbrushing", listOf("air"))),
                ),
                mutableMapOf(
                    "brushes/inking/pen" to brush("pen", "Pen"),
                    "brushes/inking/marker" to brush("marker", "Marker"),
                    "brushes/airbrushing/air" to brush("air", "Air"),
                ),
            )
    }

    @After
    fun tearDown() {
        openDatabases.forEach { it.close() }
        directory.deleteRecursively()
    }

    private fun openLibrary(dispatcher: kotlinx.coroutines.CoroutineDispatcher): BrushLibrary {
        val database =
            Room
                .databaseBuilder(context, BrushLibraryDatabase::class.java, File(directory, "library.db").absolutePath)
                .build()
                .also(openDatabases::add)
        return BrushLibrary(database, source, File(directory, "brushes"), dispatcher)
    }

    @Test
    fun seedsBuiltInGroupsInOrderWithToolDefaults() =
        runTest {
            val library = openLibrary(StandardTestDispatcher(testScheduler))
            library.initialize()
            library.initialize()
            val groups = library.groups.first()
            assertEquals(listOf("inking", "airbrushing"), groups.map { it.builtInKey })
            assertEquals(listOf("Pen", "Marker"), groups[0].brushes.map { it.name })
            assertEquals("pen", library.brushFor(BrushTool.Paint))
            assertEquals("air", library.brushFor(BrushTool.Erase))
        }

    @Test
    fun newBuiltInBrushesArriveButDeletedOnesStayDeleted() =
        runTest {
            val library = openLibrary(StandardTestDispatcher(testScheduler))
            library.initialize()
            library.delete("marker")
            source.packages["brushes/inking/fine"] = brush("fine", "Fine")
            source.library = source.library.copy(version = 2, groups = listOf(BuiltInGroup("inking", listOf("pen", "marker", "fine"))))
            library.initialize()
            assertEquals(listOf("Pen", "Fine"), library.groups.first()[0].brushes.map { it.name })
        }

    @Test
    fun editingABuiltInBrushIsCopyOnWriteAndResetRestoresIt() =
        runTest {
            val library = openLibrary(StandardTestDispatcher(testScheduler))
            library.initialize()
            val edited = library.loadPackage("pen").let { it.withDocument(it.document.with("path.spacing", 0.5).with("name", "My Pen")) }
            library.save(edited)
            assertEquals(0.5, library.loadPackage("pen").document.number("path.spacing"))
            assertEquals("My Pen", library.entry("pen")?.name)
            assertTrue(library.entry("pen")!!.hasOverride)
            assertTrue(library.reset("pen"))
            assertEquals(0.1, library.loadPackage("pen").document.number("path.spacing"))
            assertEquals("Pen", library.entry("pen")?.name)
            assertFalse(File(directory, "brushes/pen").exists())
        }

    @Test
    fun librarySurvivesARestart() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val first = openLibrary(dispatcher)
            first.initialize()
            val groupId = first.createGroup("Mine")
            val copy = first.duplicate("pen")
            first.move(copy, groupId, 0)
            first.select(BrushTool.Paint, copy)
            first.saveSliderState(copy, BrushTool.Paint, SliderState(0.7f, 0.4f))
            openDatabases.removeAt(0).close()

            val second = openLibrary(dispatcher)
            second.initialize()
            val groups = second.groups.first()
            assertEquals(listOf("Mine", null, null), groups.map { it.name })
            assertEquals(listOf(copy), groups[0].brushes.map { it.brushId })
            assertEquals(copy, second.brushFor(BrushTool.Paint))
            assertEquals(SliderState(0.7f, 0.4f), second.sliderState(copy, BrushTool.Paint))
            assertArrayEquals(byteArrayOf(1, 2, 3), second.loadPackage(copy).shapeTextureBytes)
            assertEquals(listOf(copy), second.recent.first().map { it.brushId })
        }

    @Test
    fun slidersStartAtTheBrushDefaultsAndPresetsAreCapped() =
        runTest {
            val library = openLibrary(StandardTestDispatcher(testScheduler))
            library.initialize()
            assertEquals(SliderState(0.5f, 1f), library.sliderState("pen", BrushTool.Paint))
            library.saveSliderPresets("pen", BrushTool.Paint, "size", listOf(0.1f, 0.2f, 0.3f, 0.4f, 0.5f, 0.6f))
            assertEquals(listOf(0.1f, 0.2f, 0.3f, 0.4f, 0.5f), library.sliderPresets("pen", BrushTool.Paint, "size"))
        }

    @Test
    fun importAndExport() =
        runTest {
            val library = openLibrary(StandardTestDispatcher(testScheduler))
            library.initialize()
            val archive = library.exportBrush("pen")
            val imported = library.importBrush(archive)
            assertNotEquals("pen", imported)
            assertEquals("Pen", library.entry(imported)?.name)
            val imports = library.groups.first().first { it.builtInKey == BrushLibrary.IMPORTED_GROUP_KEY }
            assertEquals(listOf(imported), imports.brushes.map { it.brushId })

            val inking = library.groups.first().first { it.builtInKey == "inking" }
            val setGroup = library.importBrushSet(library.exportGroup(inking.id, "Inks"))
            val set = library.groups.first().first { it.id == setGroup }
            assertEquals("Inks", set.name)
            assertEquals(listOf("Pen", "Marker"), set.brushes.map { it.name })

            library.deleteGroup(setGroup)
            assertNull(library.groups.first().firstOrNull { it.id == setGroup })
        }
}
