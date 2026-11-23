package com.mobyle.nanquim.di

import android.content.Context
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
import dagger.Lazy
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Singleton

/** The engine and everything that draws with it: the open canvas, brushes, colors, gradients and fonts. */
@Module
@InstallIn(SingletonComponent::class)
object CanvasModule {
    private const val COLOR_SAVE_INTERVAL_MILLIS = 500L

    @Provides
    @Singleton
    fun memoryBudgetPolicy(
        @ApplicationContext context: Context,
    ): MemoryBudgetPolicy =
        MemoryBudgetPolicy(
            maximumBytes = (context.memoryInfo().totalMem * MemoryBudgetPolicy.DEFAULT_FRACTION_OF_TOTAL_RAM).toLong(),
            minimumBytes = MemoryBudgetPolicy.DEFAULT_MINIMUM_BYTES,
        )

    /** Not scoped here: [EngineHost] creates the one engine on first use. */
    @Provides
    fun engine(host: EngineHost): Engine = host.engine

    @Provides
    @Singleton
    fun brushLibrary(
        @ApplicationContext context: Context,
    ): BrushLibrary =
        BrushLibrary(
            database = BrushLibraryDatabase.open(context),
            builtIns = AssetBrushSource(context.assets),
            storageDirectory = File(context.filesDir, "brushes"),
        )

    @Provides
    @Singleton
    fun gradientLibrary(
        @ApplicationContext context: Context,
        @ApplicationScope scope: CoroutineScope,
    ): GradientLibrary =
        GradientLibrary(File(context.filesDir, "gradients")).also { library ->
            scope.launch {
                val loaded = withContext(Dispatchers.IO) { library.load() }
                library.publish(loaded)
            }
        }

    @Provides
    @Singleton
    fun paletteLibrary(
        @ApplicationContext context: Context,
        @ApplicationScope scope: CoroutineScope,
    ): PaletteLibrary =
        PaletteLibrary(File(context.filesDir, "palettes"), { builtInPalettes(context::getString) }).also { library ->
            scope.launch { library.load() }
        }

    @Provides
    @Singleton
    fun colorProfileLibrary(
        @ApplicationContext context: Context,
        @ApplicationScope scope: CoroutineScope,
    ): ColorProfileLibrary =
        ColorProfileLibrary(File(context.filesDir, "color-profiles"), { name ->
            context.assets.open("${ColorProfileLibrary.ASSET_FOLDER}/$name").use { it.readBytes() }
        }).also { library -> scope.launch { library.load() } }

    @Provides
    @Singleton
    fun fontLibrary(
        @ApplicationContext context: Context,
        engine: Engine,
        @ApplicationScope scope: CoroutineScope,
    ): FontLibrary =
        FontLibrary(context.assets, File(context.noBackupFilesDir, "fonts-bundled"), File(context.filesDir, "fonts"), engine).also { library ->
            scope.launch { library.load() }
        }

    @Provides
    @Singleton
    fun canvasSession(
        @ApplicationContext context: Context,
        engine: Engine,
        brushLibrary: BrushLibrary,
        memoryBudgetPolicy: MemoryBudgetPolicy,
        gradientLibrary: GradientLibrary,
        paletteLibrary: PaletteLibrary,
        colorProfileLibrary: ColorProfileLibrary,
        inputSettings: InputSettingsRepository,
        fontLibrary: FontLibrary,
        appSettings: AppSettingsRepository,
        // The gallery holds the session too; it is only needed once a save finishes, long after both exist.
        gallery: Lazy<Gallery>,
        @ApplicationScope scope: CoroutineScope,
    ): CanvasSession {
        val brushes = ActiveBrushes(engine, brushLibrary, scope)
        brushes.start()
        val persistence =
            DocumentPersistence(engine, scope, BuildConfig.VERSION_NAME) { artwork, result ->
                scope.launch { gallery.get().onArtworkSaved(artwork, result) }
            }
        return CanvasSession(
            engine,
            memoryBudgetPolicy,
            brushes,
            brushLibrary,
            gradientLibrary,
            paletteLibrary,
            colorProfileLibrary,
            ResourceLayerNames(context),
            scope,
            persistence,
            context.contentResolver,
        ).also {
            it.stylusRollReported = inputSettings.stylusRollReported
            it.onStylusRollDetected = inputSettings::reportStylusRoll
            it.quickShapeDelay = { inputSettings.quickShapeDelayMillis.takeIf { inputSettings.quickShapeEnabled } }
            it.palmSupport = { inputSettings.palmSupport }
            it.hapticsEnabled = { inputSettings.hapticsEnabled }
            it.gestureConfiguration = { inputSettings.gestures }
            it.fontLibrary = fontLibrary
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

    @Provides
    @Singleton
    fun brushUi(
        @ApplicationContext context: Context,
        brushLibrary: BrushLibrary,
        canvasSession: CanvasSession,
        inputSettings: InputSettingsRepository,
        @ApplicationScope scope: CoroutineScope,
    ): BrushUiState =
        BrushUiState(
            library = brushLibrary,
            brushes = canvasSession.brushes,
            thumbnails = BrushThumbnails(brushLibrary, File(context.cacheDir, "brush-thumbnails")),
            inputSettings = inputSettings,
            scope = scope,
        )
}
