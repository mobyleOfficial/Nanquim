package com.mobyle.nanquim.di

import com.mobyle.nanquim.BuildConfig
import com.mobyle.nanquim.data.adjust.GradientLibrary
import com.mobyle.nanquim.data.brush.BrushThumbnails
import com.mobyle.nanquim.data.brush.library.AssetBrushSource
import com.mobyle.nanquim.data.brush.library.BrushLibrary
import com.mobyle.nanquim.data.brush.library.BrushLibraryDatabase
import com.mobyle.nanquim.data.canvas.ResourceLayerNames
import com.mobyle.nanquim.data.color.ColorProfileLibrary
import com.mobyle.nanquim.data.color.PaletteLibrary
import com.mobyle.nanquim.data.color.builtInPalettes
import com.mobyle.nanquim.data.document.DocumentPersistence
import com.mobyle.nanquim.data.engine.EngineHost
import com.mobyle.nanquim.data.engine.memoryInfo
import com.mobyle.nanquim.data.gallery.Gallery
import com.mobyle.nanquim.data.text.FontLibrary
import com.mobyle.nanquim.document.color.ColorState
import com.mobyle.nanquim.domain.brush.ActiveBrushes
import com.mobyle.nanquim.domain.canvas.CanvasSession
import com.mobyle.nanquim.domain.canvas.MemoryBudgetPolicy
import com.mobyle.nanquim.domain.settings.repositories.AppSettingsRepository
import com.mobyle.nanquim.domain.settings.repositories.InputSettingsRepository
import com.mobyle.nanquim.engine.Engine
import com.mobyle.nanquim.presentation.brush.BrushUiState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.android.ext.koin.androidContext
import org.koin.dsl.module
import java.io.File

private const val COLOR_SAVE_INTERVAL_MILLIS = 500L

/** The engine and everything that draws with it: the open canvas, brushes, colors, gradients and fonts. */
val canvasModule =
    module {
        single {
            MemoryBudgetPolicy(
                maximumBytes = (androidContext().memoryInfo().totalMem * MemoryBudgetPolicy.DEFAULT_FRACTION_OF_TOTAL_RAM).toLong(),
                minimumBytes = MemoryBudgetPolicy.DEFAULT_MINIMUM_BYTES,
            )
        }

        single { EngineHost(androidContext(), get(), get(), get(), get(ApplicationScope)) }

        /** Not a single here: [EngineHost] creates the one engine on first use. */
        factory<Engine> { get<EngineHost>().engine }

        single {
            val context = androidContext()
            BrushLibrary(
                database = BrushLibraryDatabase.open(context),
                builtIns = AssetBrushSource(context.assets),
                storageDirectory = File(context.filesDir, "brushes"),
            )
        }

        single {
            val scope = get<CoroutineScope>(ApplicationScope)
            GradientLibrary(File(androidContext().filesDir, "gradients")).also { library ->
                scope.launch {
                    val loaded = withContext(Dispatchers.IO) { library.load() }
                    library.publish(loaded)
                }
            }
        }

        single {
            val context = androidContext()
            val scope = get<CoroutineScope>(ApplicationScope)
            PaletteLibrary(File(context.filesDir, "palettes"), { builtInPalettes(context::getString) }).also { library ->
                scope.launch { library.load() }
            }
        }

        single {
            val context = androidContext()
            val scope = get<CoroutineScope>(ApplicationScope)
            ColorProfileLibrary(File(context.filesDir, "color-profiles"), { name ->
                context.assets.open("${ColorProfileLibrary.ASSET_FOLDER}/$name").use { it.readBytes() }
            }).also { library -> scope.launch { library.load() } }
        }

        single {
            val context = androidContext()
            val scope = get<CoroutineScope>(ApplicationScope)
            val bundled = File(context.noBackupFilesDir, "fonts-bundled")
            FontLibrary(context.assets, bundled, File(context.filesDir, "fonts"), get()).also { library ->
                scope.launch { library.load() }
            }
        }

        single {
            val context = androidContext()
            val engine = get<Engine>()
            val brushLibrary = get<BrushLibrary>()
            val inputSettings = get<InputSettingsRepository>()
            val appSettings = get<AppSettingsRepository>()
            val scope = get<CoroutineScope>(ApplicationScope)
            // The gallery holds the session too; it is only needed once a save finishes, long after both exist.
            val gallery = inject<Gallery>()
            val brushes = ActiveBrushes(engine, brushLibrary, scope)
            brushes.start()
            val persistence =
                DocumentPersistence(engine, scope, BuildConfig.VERSION_NAME) { artwork, result ->
                    scope.launch { gallery.value.onArtworkSaved(artwork, result) }
                }
            CanvasSession(
                engine,
                get(),
                brushes,
                brushLibrary,
                get(),
                get(),
                get(),
                ResourceLayerNames(context),
                scope,
                persistence,
                get(),
            ).also {
                it.stylusRollReported = inputSettings.stylusRollReported
                it.onStylusRollDetected = inputSettings::reportStylusRoll
                it.quickShapeDelay = { inputSettings.quickShapeDelayMillis.takeIf { inputSettings.quickShapeEnabled } }
                it.palmSupport = { inputSettings.palmSupport }
                it.hapticsEnabled = { inputSettings.hapticsEnabled }
                it.gestureConfiguration = { inputSettings.gestures }
                it.touchModeOn = { inputSettings.fingerPainting }
                it.fontLibrary = get()
                it.restoreColors(appSettings.colors)
                val colorSaves = Channel<ColorState>(Channel.CONFLATED)
                it.onColorsChanged = { state -> colorSaves.trySend(state) }
                scope.launch {
                    for (state in colorSaves) {
                        appSettings.saveColors(state)
                        delay(COLOR_SAVE_INTERVAL_MILLIS)
                    }
                }
            }
        }

        single {
            val brushLibrary = get<BrushLibrary>()
            BrushUiState(
                library = brushLibrary,
                brushes = get<CanvasSession>().brushes,
                thumbnails = BrushThumbnails(brushLibrary, File(androidContext().cacheDir, "brush-thumbnails")),
                inputSettings = get(),
                scope = get(ApplicationScope),
            )
        }
    }
