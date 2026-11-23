package com.mobyle.nanquim.di

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.work.WorkManager
import com.mobyle.nanquim.R
import com.mobyle.nanquim.core.navigation.AppNavigator
import com.mobyle.nanquim.core.telemetry.Telemetry
import com.mobyle.nanquim.data.adjust.GradientLibrary
import com.mobyle.nanquim.data.brush.builtInGroupNameRes
import com.mobyle.nanquim.data.color.ColorProfileLibrary
import com.mobyle.nanquim.data.color.PaletteLibrary
import com.mobyle.nanquim.data.diagnostics.DiagnosticsCollector
import com.mobyle.nanquim.data.diagnostics.DiagnosticsExporter
import com.mobyle.nanquim.data.diagnostics.ShapeTraceRecorder
import com.mobyle.nanquim.data.diagnostics.TouchSessionRecorder
import com.mobyle.nanquim.data.feedback.AppDetails
import com.mobyle.nanquim.data.feedback.FeedbackActions
import com.mobyle.nanquim.data.feedback.ReviewPrompt
import com.mobyle.nanquim.data.gallery.Gallery
import com.mobyle.nanquim.data.gallery.SampleArtworks
import com.mobyle.nanquim.data.help.Help
import com.mobyle.nanquim.data.macro.Macros
import com.mobyle.nanquim.data.settings.AppSettings
import com.mobyle.nanquim.data.settings.KeyboardShortcutSettings
import com.mobyle.nanquim.data.settings.QuickMenuSettings
import com.mobyle.nanquim.data.settings.StorageCleaner
import com.mobyle.nanquim.data.text.FontLibrary
import com.mobyle.nanquim.data.transfer.ArtworkTransfer
import com.mobyle.nanquim.document.command.CommandContext
import com.mobyle.nanquim.document.command.CommandResult
import com.mobyle.nanquim.domain.canvas.CanvasSession
import com.mobyle.nanquim.domain.canvas.MemoryBudgetPolicy
import com.mobyle.nanquim.domain.command.CommandDispatcher
import com.mobyle.nanquim.domain.command.FeedbackTexts
import com.mobyle.nanquim.domain.command.NanquimAppState
import com.mobyle.nanquim.domain.command.ShareSheet
import com.mobyle.nanquim.domain.command.TimelapseCommandIds
import com.mobyle.nanquim.domain.command.TransferCommandIds
import com.mobyle.nanquim.domain.command.createAppCommandRegistry
import com.mobyle.nanquim.domain.command.registerFeedbackCommands
import com.mobyle.nanquim.domain.command.registerHelpCommands
import com.mobyle.nanquim.domain.diagnostics.StylusObservationRecorder
import com.mobyle.nanquim.domain.feedback.AppLinks
import com.mobyle.nanquim.domain.feedback.FeedbackKind
import com.mobyle.nanquim.presentation.brush.BrushUiState
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import javax.inject.Singleton

/**
 * The command registry every way of acting on the app goes through: the screens, the keyboard, macros, the quick
 * menu and automation all dispatch the same commands.
 */
@Module
@InstallIn(SingletonComponent::class)
object CommandModule {
    /** Commands after which the person has just finished something they liked: a good moment to ask for a review. */
    private val REVIEW_MOMENTS =
        setOf(
            TransferCommandIds.SHARE,
            TransferCommandIds.EXPORT_IMAGE,
            TimelapseCommandIds.EXPORT,
        )

    @Provides
    @Singleton
    fun commandDispatcher(
        @ApplicationContext context: Context,
        navigator: AppNavigator,
        canvasSession: CanvasSession,
        gallery: Gallery,
        transfer: ArtworkTransfer,
        appSettings: AppSettings,
        storageCleaner: StorageCleaner,
        memoryBudgetPolicy: MemoryBudgetPolicy,
        brushUi: BrushUiState,
        quickMenu: QuickMenuSettings,
        keyboardShortcuts: KeyboardShortcutSettings,
        macros: Macros,
        gradients: GradientLibrary,
        palettes: PaletteLibrary,
        colorProfiles: ColorProfileLibrary,
        fonts: FontLibrary,
        diagnosticsCollector: DiagnosticsCollector,
        stylusObservationRecorder: StylusObservationRecorder,
        shapeTraces: ShapeTraceRecorder,
        touchSessions: TouchSessionRecorder,
        help: Help,
        sampleArtworks: SampleArtworks,
        feedbackActions: FeedbackActions,
        appDetails: AppDetails,
        telemetry: Telemetry,
        reviewPrompt: ReviewPrompt,
        @ApplicationScope scope: CoroutineScope,
    ): CommandDispatcher {
        val appState = NanquimAppState(canvasSession)
        val registry =
            createAppCommandRegistry(
                navigator = navigator,
                canvasSession = canvasSession,
                gallery = gallery,
                transfer = transfer,
                cacheDirectory = context.cacheDir,
                shareSheet = ShareSheet(context),
                appSettings = appSettings,
                storageCleaner = storageCleaner,
                memoryBudgetBytes = { memoryBudgetPolicy.maximumBytes },
                brushUi = brushUi,
                quickMenu = quickMenu,
                keyboardShortcuts = keyboardShortcuts,
                macros = macros,
                gradients = gradients,
                palettes = palettes,
                colorProfiles = colorProfiles,
                fonts = fonts,
                contentResolver = context.contentResolver,
                diagnosticsExporter = DiagnosticsExporter(context.contentResolver, diagnosticsCollector, stylusObservationRecorder),
                shapeTraces = shapeTraces,
                touchSessions = touchSessions,
                brushGroupName = { group -> group.name ?: builtInGroupNameRes(group)?.let(context::getString) ?: group.builtInKey.orEmpty() },
                stampBrushName = { context.getString(R.string.brush_stamp_name) },
                clipboardImage = {
                    context
                        .getSystemService(ClipboardManager::class.java)
                        ?.primaryClip
                        ?.takeIf { it.itemCount > 0 }
                        ?.getItemAt(0)
                        ?.uri
                },
                workManager = WorkManager.getInstance(context),
            )
        registerHelpCommands(
            registry = registry,
            help = help,
            manualUrl = { context.getString(R.string.help_manual_url) },
            openUrl = { url ->
                runCatching {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }.isSuccess
            },
            installSamples = sampleArtworks::install,
            supportLinks = appDetails::supportLinks,
        )
        registerFeedbackCommands(
            registry = registry,
            actions = feedbackActions,
            appSettings = appSettings,
            appInfo = appDetails::appInfo,
            texts =
                FeedbackTexts(
                    shareApp = { context.getString(R.string.share_app_text, AppLinks.PLAY_STORE_WEB) },
                    emailSubject = { kind ->
                        context.getString(
                            if (kind == FeedbackKind.Bug) R.string.feedback_email_subject_bug else R.string.feedback_email_subject_feature,
                        )
                    },
                    emailBody = { kind, info ->
                        context.getString(
                            if (kind == FeedbackKind.Bug) R.string.feedback_email_body_bug else R.string.feedback_email_body_feature,
                            info.summary(),
                        )
                    },
                ),
            onUsageStatisticsChanged = telemetry::setCollectionEnabled,
        )
        registry.onExecuted = { commandId, arguments, result ->
            if (result is CommandResult.Success) {
                macros.record(commandId, arguments)
                if (commandId in REVIEW_MOMENTS) reviewPrompt.artworkExported()
            }
        }
        return CommandDispatcher(
            registry = registry,
            context =
                object : CommandContext {
                    override val appState = appState
                },
            scope = scope,
        )
    }
}
