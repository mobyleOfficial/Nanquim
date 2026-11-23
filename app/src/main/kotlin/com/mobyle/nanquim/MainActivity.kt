package com.mobyle.nanquim

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.Process
import android.os.SystemClock
import android.view.Choreographer
import android.view.KeyEvent
import android.view.KeyboardShortcutGroup
import android.view.Menu
import android.view.Window
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.snapshotFlow
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.mobyle.nanquim.core.automation.ControlChannelHost
import com.mobyle.nanquim.core.navigation.AppDestination
import com.mobyle.nanquim.core.navigation.AppNavigator
import com.mobyle.nanquim.core.telemetry.Telemetry
import com.mobyle.nanquim.data.ads.GalleryAds
import com.mobyle.nanquim.data.canvas.ResumeState
import com.mobyle.nanquim.data.feedback.ReviewPrompt
import com.mobyle.nanquim.data.gallery.Gallery
import com.mobyle.nanquim.data.gallery.SampleArtworks
import com.mobyle.nanquim.data.help.Help
import com.mobyle.nanquim.domain.canvas.CanvasSession
import com.mobyle.nanquim.domain.command.CanvasCommandIds
import com.mobyle.nanquim.domain.command.CommandDispatcher
import com.mobyle.nanquim.domain.settings.models.ThemeMode
import com.mobyle.nanquim.domain.settings.repositories.AppSettingsRepository
import com.mobyle.nanquim.domain.settings.repositories.KeyboardShortcutsRepository
import com.mobyle.nanquim.engine.Engine
import com.mobyle.nanquim.presentation.common.ProvideWindowLayout
import com.mobyle.nanquim.presentation.display.ProjectedCanvas
import com.mobyle.nanquim.presentation.feedback.requestInAppReview
import com.mobyle.nanquim.presentation.keyboard.KeyboardInput
import com.mobyle.nanquim.presentation.shell.LaunchState
import com.mobyle.nanquim.presentation.shell.NanquimApp
import com.mobyle.nanquim.presentation.theme.NanquimTheme
import dagger.Lazy
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import javax.inject.Inject

/**
 * Hosts [NanquimApp] and does what needs the activity itself: launch intents, the window, the hardware keyboard, the
 * second display, saving when it leaves the screen. The heavy objects are injected lazily so they are created in the
 * same order, and no sooner, than the screens ask for them.
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject lateinit var launchState: LaunchState

    @Inject lateinit var navigator: AppNavigator

    @Inject lateinit var controlChannelHost: ControlChannelHost

    @Inject lateinit var telemetry: Telemetry

    @Inject lateinit var appSettings: Lazy<AppSettingsRepository>

    @Inject lateinit var gallery: Lazy<Gallery>

    @Inject lateinit var help: Lazy<Help>

    @Inject lateinit var sampleArtworks: Lazy<SampleArtworks>

    @Inject lateinit var canvasSession: Lazy<CanvasSession>

    @Inject lateinit var resumeState: Lazy<ResumeState>

    @Inject lateinit var reviewPrompt: Lazy<ReviewPrompt>

    @Inject lateinit var galleryAds: Lazy<GalleryAds>

    @Inject lateinit var keyboardShortcutSettings: Lazy<KeyboardShortcutsRepository>

    @Inject lateinit var commandDispatcher: Lazy<CommandDispatcher>

    @Inject lateinit var engine: Lazy<Engine>

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(R.style.Theme_Nanquim)
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (!launchState.appStartReported) {
            launchState.appStartReported = true
            if (savedInstanceState == null) measureAppStart(SystemClock.uptimeMillis())
        }
        setContent {
            val darkTheme =
                when (appSettings.get().themeMode) {
                    ThemeMode.System -> isSystemInDarkTheme()
                    ThemeMode.Light -> false
                    ThemeMode.Dark -> true
                }
            // The default edge-to-edge style follows the system's dark mode, so a Light app on a dark
            // system kept white status bar icons on a light background. Follow the app's theme instead.
            DisposableEffect(darkTheme) {
                val style = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { darkTheme }
                enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
                onDispose {}
            }
            NanquimTheme(darkTheme = darkTheme) {
                ProvideWindowLayout { NanquimApp() }
            }
        }
        // Once per process, not once per fresh activity: after the system ends the process in the background, the activity
        // comes back with a saved state but everything else starts from scratch, and the open artwork must come back too.
        val processStart = !launchState.launchHandled
        launchState.launchHandled = true
        if (processStart && !openTestDocumentFromIntent(intent)) {
            val savedScreens = savedInstanceState?.getStringArrayList(STATE_SCREENS)
            lifecycleScope.launch {
                val gallery = gallery.get()
                val session = canvasSession.get()
                val (screenWidth, screenHeight) = displayResolution()
                gallery.seedPresets(screenWidth, screenHeight)
                gallery.sync()
                if (help.get().automatic) sampleArtworks.get().installOnce()
                val requested = if (savedInstanceState == null) takeArtworkToOpen(intent) else null
                if (requested != null) {
                    gallery.openFromOutside(requested)
                } else if (session.documentSession == null && gallery.restoreLastOpen()) {
                    session.artwork
                        ?.id
                        ?.let { resumeState.get().restore(session, it) }
                }
                savedScreens?.let { navigator.restore(it, artworkOpen = session.documentSession != null) }
            }
        }
        val windowCallback = window.callback
        window.callback =
            object : Window.Callback by windowCallback {
                override fun dispatchKeyEvent(event: KeyEvent): Boolean {
                    val typing = currentFocus?.onCheckIsTextEditor() == true
                    return (onCanvas && !typing && keyboard.onKeyEvent(event)) || windowCallback.dispatchKeyEvent(event)
                }
            }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                snapshotFlow { navigator.current }.collect { destination ->
                    telemetry.screenShown(destination.name)
                }
            }
        }
        lifecycleScope.launch {
            snapshotFlow { navigator.current == AppDestination.Canvas }.collect { immersive ->
                val controller = WindowCompat.getInsetsController(window, window.decorView)
                if (immersive) {
                    controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                    controller.hide(WindowInsetsCompat.Type.systemBars())
                } else {
                    controller.show(WindowInsetsCompat.Type.systemBars())
                }
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                val reviewPrompt = reviewPrompt.get()
                val help = help.get()
                snapshotFlow {
                    reviewPrompt.pending && navigator.current == AppDestination.Gallery && help.automatic
                }.collect { ready ->
                    if (ready) {
                        reviewPrompt.asked()
                        requestInAppReview(this@MainActivity)
                    }
                }
            }
        }
        controlChannelHost.start(intent?.getStringExtra(EXTRA_CONTROL_TOKEN))
        // Before the wait below: lifecycleScope starts it immediately, so the cards must already count as shown.
        if (savedInstanceState == null) help.get().showWelcomeIfFirstLaunch()
        if (savedInstanceState == null) incomingFileOf(intent)?.let { launchState.incomingFile = it }
        lifecycleScope.launch {
            val help = help.get()
            // Consent and ads wait for the introduction to end and the gallery to show, so nothing interrupts a first launch.
            snapshotFlow { !help.welcomeVisible && navigator.current == AppDestination.Gallery }.first { it }
            if (!help.automatic) return@launch
            val galleryAds = galleryAds.get()
            // The one-time note on ads (shown by NanquimApp) comes before any consent form or banner.
            if (galleryAds.enabled) snapshotFlow { appSettings.get().adsNoticeSeen }.first { it }
            galleryAds.start(this@MainActivity)
        }
    }

    private fun measureAppStart(activityCreatedAt: Long) {
        lifecycleScope.launch {
            val gallery = gallery.get()
            val session = canvasSession.get()
            snapshotFlow { gallery.loaded || session.documentShown }.first { it }
            suspendCancellableCoroutine { continuation ->
                Choreographer.getInstance().postFrameCallback { continuation.resumeWith(Result.success(Unit)) }
            }
            val now = SystemClock.uptimeMillis()
            telemetry.appStarted(now - Process.getStartUptimeMillis(), now - activityCreatedAt)
        }
    }

    private fun incomingFileOf(intent: Intent?): android.net.Uri? =
        when (intent?.action) {
            Intent.ACTION_VIEW -> intent.data
            Intent.ACTION_SEND ->
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(Intent.EXTRA_STREAM, android.net.Uri::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(Intent.EXTRA_STREAM)
                }
            else -> null
        }?.takeIf { it.scheme == "content" || it.scheme == "file" }

    private fun displayResolution(): Pair<Int, Int> =
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            val bounds = windowManager.maximumWindowMetrics.bounds
            bounds.width() to bounds.height()
        } else {
            val size = android.graphics.Point()
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.getRealSize(size)
            size.x to size.y
        }

    private val keyboard by lazy {
        KeyboardInput(
            session = canvasSession.get(),
            shortcuts = { keyboardShortcutSettings.get().shortcuts },
            dispatch = { commandId, arguments -> commandDispatcher.get().dispatch(commandId, arguments) },
        )
    }

    private val onCanvas: Boolean get() = navigator.current == AppDestination.Canvas

    override fun onProvideKeyboardShortcuts(
        data: MutableList<KeyboardShortcutGroup>,
        menu: Menu?,
        deviceId: Int,
    ) {
        super.onProvideKeyboardShortcuts(data, menu, deviceId)
        val shortcuts = keyboardShortcutSettings.get().shortcuts
        data += KeyboardInput.shortcutGroup(shortcuts, resources, commandDispatcher.get().registry)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        incomingFileOf(intent)?.let { launchState.incomingFile = it }
        takeArtworkToOpen(intent)?.let { id -> lifecycleScope.launch { gallery.get().openFromOutside(id) } }
    }

    private fun takeArtworkToOpen(intent: Intent?): String? =
        intent?.getStringExtra(EXTRA_OPEN_ARTWORK)?.also { intent.removeExtra(EXTRA_OPEN_ARTWORK) }

    private var projectedCanvas: ProjectedCanvas? = null

    override fun onStart() {
        super.onStart()
        val appSettings = appSettings.get()
        projectedCanvas =
            ProjectedCanvas(
                activity = this,
                engine = engine.get(),
                session = canvasSession.get(),
                enabled = { appSettings.projectedCanvas },
                scope = lifecycleScope,
            ).also { it.start() }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putStringArrayList(STATE_SCREENS, ArrayList(navigator.snapshot()))
    }

    override fun onStop() {
        projectedCanvas?.stop()
        projectedCanvas = null
        super.onStop()
        val session = canvasSession.get()
        val state = session.artwork?.let { session.document }
        resumeState.get().save(session)
        val startedAt = SystemClock.elapsedRealtime()
        session.persistence.saveNow(withThumbnail = true) { saved ->
            if (state != null) telemetry.artworkSavedInBackground(state, SystemClock.elapsedRealtime() - startedAt, saved)
        }
        session.timelapse.pause()
    }

    private fun openTestDocumentFromIntent(intent: Intent?): Boolean {
        if (!BuildConfig.DEVELOPER_TOOLS) return false
        val specification = intent?.getStringExtra(EXTRA_TEST_DOCUMENT) ?: return false
        val parts = specification.split('x').mapNotNull(String::toIntOrNull)
        if (parts.size != 3) return false
        navigator.showCanvas()
        val fill = intent.getIntExtra(EXTRA_TEST_FILL, CanvasSession.FILL_ALL_TILES)
        commandDispatcher.get().dispatch(
            CanvasCommandIds.DEBUG_CREATE_TEST_DOCUMENT,
            buildJsonObject {
                put("width", parts[0])
                put("height", parts[1])
                put("layers", parts[2])
                put("fillTiles", fill)
            },
        )
        return true
    }

    companion object {
        const val EXTRA_OPEN_ARTWORK = "com.mobyle.nanquim.extra.OPEN_ARTWORK"

        private const val EXTRA_TEST_DOCUMENT = "com.mobyle.nanquim.extra.TEST_DOCUMENT"

        private const val EXTRA_CONTROL_TOKEN = "com.mobyle.nanquim.extra.CONTROL_TOKEN"
        private const val EXTRA_TEST_FILL = "com.mobyle.nanquim.extra.TEST_FILL"
        private const val STATE_SCREENS = "screens"
    }
}
