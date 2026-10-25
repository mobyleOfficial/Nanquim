package com.mobyle.nanquim

import android.app.ActivityManager
import android.app.Application
import android.content.ComponentCallbacks2
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.mobyle.nanquim.color.ColorProfileLibrary
import com.mobyle.nanquim.color.PaletteLibrary
import com.mobyle.nanquim.color.builtInPalettes
import com.mobyle.nanquim.text.FontLibrary
import com.mobyle.nanquim.document.color.ColorState
import com.mobyle.nanquim.canvas.adjust.GradientLibrary
import com.mobyle.nanquim.automation.CanvasAutomationHost
import com.mobyle.nanquim.automation.ControlChannel
import com.mobyle.nanquim.brush.ActiveBrushes
import com.mobyle.nanquim.brush.BrushThumbnails
import com.mobyle.nanquim.brush.BrushUiState
import com.mobyle.nanquim.brush.builtInGroupNameRes
import com.mobyle.nanquim.brush.library.AssetBrushSource
import com.mobyle.nanquim.brush.library.BrushLibrary
import com.mobyle.nanquim.brush.library.BrushLibraryDatabase
import com.mobyle.nanquim.canvas.CanvasSession
import com.mobyle.nanquim.canvas.MemoryBudgetPolicy
import com.mobyle.nanquim.canvas.ResourceLayerNames
import com.mobyle.nanquim.command.CommandDispatcher
import com.mobyle.nanquim.command.NanquimAppState
import com.mobyle.nanquim.command.ShareSheet
import com.mobyle.nanquim.command.createAppCommandRegistry
import com.mobyle.nanquim.command.registerHelpCommands
import com.mobyle.nanquim.help.Help
import com.mobyle.nanquim.diagnostics.DiagnosticsCollector
import com.mobyle.nanquim.diagnostics.DiagnosticsExporter
import com.mobyle.nanquim.diagnostics.ShapeTraceRecorder
import com.mobyle.nanquim.diagnostics.TouchSessionRecorder
import com.mobyle.nanquim.diagnostics.StylusObservationRecorder
import com.mobyle.nanquim.document.ArtworkStorage
import com.mobyle.nanquim.document.DocumentPersistence
import com.mobyle.nanquim.gallery.ArtworkTransfer
import com.mobyle.nanquim.gallery.Gallery
import com.mobyle.nanquim.gallery.GalleryDatabase
import com.mobyle.nanquim.gallery.GalleryDocumentsProvider
import com.mobyle.nanquim.gallery.GalleryNames
import com.mobyle.nanquim.gallery.SampleArtworks
import com.mobyle.nanquim.ui.settings.SupportLink
import com.mobyle.nanquim.ui.settings.parseSupportLinks
import com.mobyle.nanquim.navigation.AppNavigator
import com.mobyle.nanquim.keyboard.KeyboardShortcutSettings
import com.mobyle.nanquim.macro.Macros
import com.mobyle.nanquim.settings.AppSettings
import com.mobyle.nanquim.settings.InputSettings
import com.mobyle.nanquim.settings.QuickMenuSettings
import com.mobyle.nanquim.settings.StorageCleaner
import com.mobyle.nanquim.telemetry.Telemetry
import com.mobyle.nanquim.widget.WidgetUpdates
import com.mobyle.nanquim.document.command.CommandContext
import com.mobyle.nanquim.document.command.CommandResult
import com.mobyle.nanquim.engine.Engine
import com.mobyle.nanquim.engine.EngineConfig
import com.mobyle.nanquim.engine.Renderer
import java.io.File
import java.util.Locale

class NanquimApplication : Application() {
    val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    val telemetry: Telemetry by lazy {
        Telemetry.create(
            this,
            analyticsEnabled = BuildConfig.BUILD_TYPE != "benchmark",
        )
    }

    var appStartReported = false

    override fun onCreate() {
        super.onCreate()
        telemetry
    }

    val memoryBudgetPolicy: MemoryBudgetPolicy by lazy {
        MemoryBudgetPolicy(
            maximumBytes = (memoryInfo().totalMem * MemoryBudgetPolicy.DEFAULT_FRACTION_OF_TOTAL_RAM).toLong(),
            minimumBytes = MemoryBudgetPolicy.DEFAULT_MINIMUM_BYTES,
        )
    }

    private var engineCreated = false

    val engine: Engine by lazy {
        engineCreated = true
        startMemoryMonitor()
        val renderer = developerRenderer() ?: appSettings.renderer
        appSettings.startedRenderer = renderer
        telemetry.memoryBudgetChanged(memoryBudgetPolicy.currentBytes)
        Engine(EngineConfig(memoryBudgetPolicy.currentBytes, scratchDirectory().absolutePath, renderer = renderer)).also { engine ->
            applicationScope.launch { engine.unrecoverableError.filterNotNull().collect(telemetry::engineFailed) }
            engine.requestGpuInfo { info ->
                applicationScope.launch {
                    val active = if (info.api == "vulkan") Renderer.Vulkan else Renderer.Gles
                    appSettings.activeRenderer = active
                    telemetry.rendererChanged(AppSettings.rendererId(renderer), AppSettings.rendererId(active))
                    if (active != renderer) {
                        Toast.makeText(this@NanquimApplication, R.string.renderer_fallback_notice, Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }

    val navigator = AppNavigator()

    val brushLibrary: BrushLibrary by lazy {
        BrushLibrary(
            database = BrushLibraryDatabase.open(this),
            builtIns = AssetBrushSource(assets),
            storageDirectory = File(filesDir, "brushes"),
        )
    }

    val gradientLibrary: GradientLibrary by lazy {
        GradientLibrary(File(filesDir, "gradients")).also { library ->
            applicationScope.launch {
                val loaded = withContext(Dispatchers.IO) { library.load() }
                library.publish(loaded)
            }
        }
    }

    val paletteLibrary: PaletteLibrary by lazy {
        PaletteLibrary(File(filesDir, "palettes"), { builtInPalettes(::getString) }).also { library ->
            applicationScope.launch { library.load() }
        }
    }

    val colorProfileLibrary: ColorProfileLibrary by lazy {
        ColorProfileLibrary(File(filesDir, "color-profiles"), { name ->
            assets.open("${ColorProfileLibrary.ASSET_FOLDER}/$name").use { it.readBytes() }
        }).also { library -> applicationScope.launch { library.load() } }
    }

    val inputSettings: InputSettings by lazy {
        InputSettings(getSharedPreferences(InputSettings.PREFERENCES_NAME, MODE_PRIVATE), engine).also { it.apply() }
    }

    val fontLibrary: FontLibrary by lazy {
        FontLibrary(assets, File(noBackupFilesDir, "fonts-bundled"), File(filesDir, "fonts"), engine).also { library ->
            applicationScope.launch { library.load() }
        }
    }

    val artworkStorage: ArtworkStorage by lazy { ArtworkStorage(File(filesDir, "artworks").apply { mkdirs() }) }

    val canvasSession: CanvasSession by lazy {
        val brushes = ActiveBrushes(engine, brushLibrary, applicationScope)
        brushes.start()
        val persistence =
            DocumentPersistence(engine, applicationScope, BuildConfig.VERSION_NAME) { artwork, result ->
                applicationScope.launch { gallery.onArtworkSaved(artwork, result) }
            }
        CanvasSession(
            engine,
            memoryBudgetPolicy,
            brushes,
            brushLibrary,
            gradientLibrary,
            paletteLibrary,
            colorProfileLibrary,
            ResourceLayerNames(this),
            applicationScope,
            persistence,
            contentResolver,
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
            applicationScope.launch {
                for (state in colorSaves) {
                    appSettings.saveColors(state)
                    delay(COLOR_SAVE_INTERVAL_MILLIS)
                }
            }
        }
    }

    val galleryDatabase: GalleryDatabase by lazy { GalleryDatabase.open(this) }

    val gallery: Gallery by lazy {
        Gallery(
            storage = artworkStorage,
            dao = galleryDatabase.dao(),
            canvasSession = canvasSession,
            navigator = navigator,
            layerNames = ResourceLayerNames(this),
            preferences = getSharedPreferences(Gallery.PREFERENCES_NAME, MODE_PRIVATE),
            names =
                object : GalleryNames {
                    override fun untitled() = getString(R.string.gallery_untitled)

                    override fun copyOf(name: String) = getString(R.string.gallery_copy_name, name)

                    override fun stack() = getString(R.string.gallery_stack_name)
                },
            scope = applicationScope,
        ).also { gallery ->
            gallery.recordTimelapse = { appSettings.recordTimelapse }
            gallery.telemetry = telemetry
            GalleryDocumentsProvider.notifyOnChanges(this, gallery.items, applicationScope)
            WidgetUpdates.observe(this, gallery.items, applicationScope)
        }
    }

    val brushUi: BrushUiState by lazy {
        BrushUiState(
            library = brushLibrary,
            brushes = canvasSession.brushes,
            thumbnails = BrushThumbnails(brushLibrary, File(cacheDir, "brush-thumbnails")),
            inputSettings = inputSettings,
            scope = applicationScope,
        )
    }

    val transfer: ArtworkTransfer by lazy {
        ArtworkTransfer(
            contentResolver = contentResolver,
            engine = engine,
            storage = artworkStorage,
            gallery = gallery,
            canvasSession = canvasSession,
            cacheDirectory = cacheDir,
            layerNames = ResourceLayerNames(this),
            untitledName = { getString(R.string.gallery_untitled) },
            backgroundLayerName = { getString(R.string.export_background_layer) },
            appVersion = BuildConfig.VERSION_NAME,
        ) { memoryBudgetPolicy.maximumBytes }
    }

    val quickMenuSettings: QuickMenuSettings by lazy {
        QuickMenuSettings(getSharedPreferences(QUICK_MENU_PREFERENCES, MODE_PRIVATE), getString(R.string.quick_menu_default_preset))
    }

    val macros: Macros by lazy { Macros(File(filesDir, Macros.FOLDER)) }

    val keyboardShortcutSettings: KeyboardShortcutSettings by lazy {
        KeyboardShortcutSettings(getSharedPreferences(KeyboardShortcutSettings.PREFERENCES_NAME, MODE_PRIVATE))
    }

    fun supportLinks(): List<SupportLink> = parseSupportLinks(resources.getStringArray(R.array.support_links).toList())

    val sampleArtworks: SampleArtworks by lazy {
        SampleArtworks(assets, transfer, gallery, getSharedPreferences(SampleArtworks.PREFERENCES_NAME, MODE_PRIVATE), cacheDir) { key ->
            when (key) {
                "night-hills" -> getString(R.string.sample_night_hills)
                "ink-flower" -> getString(R.string.sample_ink_flower)
                "bouncing-ball" -> getString(R.string.sample_bouncing_ball)
                else -> null
            }
        }
    }

    val help: Help by lazy { Help(getSharedPreferences(Help.PREFERENCES_NAME, MODE_PRIVATE)) }

    val appSettings: AppSettings by lazy { AppSettings(getSharedPreferences(AppSettings.PREFERENCES_NAME, MODE_PRIVATE)) }

    val storageCleaner: StorageCleaner by lazy { StorageCleaner(artworkStorage.root, cacheDir) }

    var incomingFile: Uri? by mutableStateOf(null)

    val stylusObservationRecorder = StylusObservationRecorder()

    val diagnosticsCollector: DiagnosticsCollector by lazy { DiagnosticsCollector(this, engine) }

    val shapeTraceRecorder: ShapeTraceRecorder by lazy {
        ShapeTraceRecorder(File(filesDir, "diagnostics/shape-traces.json"), "${Build.MANUFACTURER} ${Build.MODEL}", applicationScope)
    }

    val touchSessionRecorder: TouchSessionRecorder by lazy {
        TouchSessionRecorder(
            File(filesDir, "diagnostics/touch-sessions.json"),
            "${Build.MANUFACTURER} ${Build.MODEL}",
            resources.displayMetrics.xdpi,
            applicationScope,
        )
    }

    val commandDispatcher: CommandDispatcher by lazy {
        val appState = NanquimAppState(canvasSession)
        val registry =
            createAppCommandRegistry(
                navigator = navigator,
                canvasSession = canvasSession,
                gallery = gallery,
                transfer = transfer,
                cacheDirectory = cacheDir,
                shareSheet = ShareSheet(this),
                appSettings = appSettings,
                storageCleaner = storageCleaner,
                memoryBudgetBytes = { memoryBudgetPolicy.maximumBytes },
                brushUi = brushUi,
                quickMenu = quickMenuSettings,
                keyboardShortcuts = keyboardShortcutSettings,
                macros = macros,
                gradients = gradientLibrary,
                palettes = paletteLibrary,
                colorProfiles = colorProfileLibrary,
                fonts = fontLibrary,
                contentResolver = contentResolver,
                diagnosticsExporter = DiagnosticsExporter(contentResolver, diagnosticsCollector, stylusObservationRecorder),
                shapeTraces = shapeTraceRecorder,
                touchSessions = touchSessionRecorder,
                brushGroupName = { group -> group.name ?: builtInGroupNameRes(group)?.let(::getString) ?: group.builtInKey.orEmpty() },
                stampBrushName = { getString(R.string.brush_stamp_name) },
                clipboardImage = {
                    getSystemService(android.content.ClipboardManager::class.java)
                        ?.primaryClip
                        ?.takeIf { it.itemCount > 0 }
                        ?.getItemAt(0)
                        ?.uri
                },
                workManager = androidx.work.WorkManager.getInstance(this),
            )
        registerHelpCommands(
            registry = registry,
            help = help,
            manualUrl = { getString(R.string.help_manual_url) },
            openUrl = { url ->
                runCatching {
                    startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, Uri.parse(url)).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
                }.isSuccess
            },
            installSamples = sampleArtworks::install,
            supportLinks = ::supportLinks,
        )
        registry.onExecuted = { commandId, arguments, result ->
            if (result is CommandResult.Success) macros.record(commandId, arguments)
        }
        CommandDispatcher(
            registry = registry,
            context =
                object : CommandContext {
                    override val appState = appState
                },
            scope = applicationScope,
        )
    }

    var controlChannel: ControlChannel? = null
        private set

    fun startControlChannel(controlToken: String?) {
        if (!BuildConfig.DEVELOPER_TOOLS || controlChannel != null) return
        if (controlToken != null) help.automatic = false
        val dispatcher = commandDispatcher
        val host = CanvasAutomationHost(canvasSession, engine, applicationScope, appSettings, help)
        val english = createConfigurationContext(Configuration(resources.configuration).apply { setLocale(Locale.ENGLISH) })
        controlChannel =
            ControlChannel(
                registry = dispatcher.registry,
                context = dispatcher.context,
                host = host,
                token = controlToken ?: ControlChannel.newToken(),
                title = { titleRes -> runCatching { english.getString(titleRes) }.getOrDefault("") },
            ).also { it.start() }
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (engineCreated && level >= ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN) {
            memoryBudgetPolicy.onBackground()?.let(::applyMemoryBudget)
        }
    }

    private fun startMemoryMonitor() {
        applicationScope.launch {
            while (true) {
                delay(MEMORY_SAMPLE_INTERVAL_MILLISECONDS)
                val info = memoryInfo()
                memoryBudgetPolicy.onMemorySample(info.availMem, info.threshold, info.lowMemory)?.let(::applyMemoryBudget)
            }
        }
    }

    private fun applyMemoryBudget(bytes: Long) {
        engine.setMemoryBudget(bytes)
        telemetry.memoryBudgetChanged(bytes)
    }

    private fun memoryInfo(): ActivityManager.MemoryInfo =
        ActivityManager.MemoryInfo().also { getSystemService(ActivityManager::class.java).getMemoryInfo(it) }

    private fun scratchDirectory(): File = File(cacheDir, "engine").apply { mkdirs() }

    private fun developerRenderer(): Renderer? {
        val value =
            runCatching {
                @Suppress("PrivateApi")
                Class.forName("android.os.SystemProperties").getMethod("get", String::class.java).invoke(null, "debug.nanquim.renderer")
            }.getOrNull() as? String
        return AppSettings.rendererFromId(value)
    }

    private companion object {
        const val COLOR_SAVE_INTERVAL_MILLIS = 500L
        const val MEMORY_SAMPLE_INTERVAL_MILLISECONDS = 2_000L
        const val QUICK_MENU_PREFERENCES = "quick-menu"
    }
}
