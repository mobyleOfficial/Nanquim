package com.mobyle.nanquim.di

import android.content.Context
import android.content.Context.MODE_PRIVATE
import android.os.Build
import com.mobyle.nanquim.BuildConfig
import com.mobyle.nanquim.R
import com.mobyle.nanquim.ads.GalleryAds
import com.mobyle.nanquim.canvas.ResumeState
import com.mobyle.nanquim.diagnostics.DiagnosticsCollector
import com.mobyle.nanquim.diagnostics.ShapeTraceRecorder
import com.mobyle.nanquim.diagnostics.StylusObservationRecorder
import com.mobyle.nanquim.diagnostics.TouchSessionRecorder
import com.mobyle.nanquim.engine.Engine
import com.mobyle.nanquim.feedback.FeedbackActions
import com.mobyle.nanquim.feedback.ReviewPrompt
import com.mobyle.nanquim.help.Help
import com.mobyle.nanquim.keyboard.KeyboardShortcutSettings
import com.mobyle.nanquim.macro.Macros
import com.mobyle.nanquim.navigation.AppNavigator
import com.mobyle.nanquim.settings.AppSettings
import com.mobyle.nanquim.settings.QuickMenuSettings
import com.mobyle.nanquim.telemetry.Telemetry
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.io.File
import javax.inject.Singleton

/** Settings, navigation, telemetry and the other application-wide objects that do not need the engine. */
@Module
@InstallIn(SingletonComponent::class)
object AppModule {
    private const val QUICK_MENU_PREFERENCES = "quick-menu"

    @Provides
    @Singleton
    @ApplicationScope
    fun applicationScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    @Provides
    @Singleton
    fun appSettings(
        @ApplicationContext context: Context,
    ): AppSettings = AppSettings(context.getSharedPreferences(AppSettings.PREFERENCES_NAME, MODE_PRIVATE))

    @Provides
    @Singleton
    fun telemetry(
        @ApplicationContext context: Context,
        appSettings: AppSettings,
    ): Telemetry =
        Telemetry.create(
            context,
            analyticsEnabled = BuildConfig.BUILD_TYPE != "benchmark",
            userAllows = appSettings.usageStatistics,
        )

    @Provides
    @Singleton
    fun navigator(): AppNavigator = AppNavigator()

    @Provides
    @Singleton
    fun quickMenuSettings(
        @ApplicationContext context: Context,
    ): QuickMenuSettings =
        QuickMenuSettings(context.getSharedPreferences(QUICK_MENU_PREFERENCES, MODE_PRIVATE), context.getString(R.string.quick_menu_default_preset))

    @Provides
    @Singleton
    fun macros(
        @ApplicationContext context: Context,
    ): Macros = Macros(File(context.filesDir, Macros.FOLDER))

    @Provides
    @Singleton
    fun keyboardShortcutSettings(
        @ApplicationContext context: Context,
    ): KeyboardShortcutSettings = KeyboardShortcutSettings(context.getSharedPreferences(KeyboardShortcutSettings.PREFERENCES_NAME, MODE_PRIVATE))

    @Provides
    @Singleton
    fun help(
        @ApplicationContext context: Context,
        telemetry: Telemetry,
    ): Help =
        Help(context.getSharedPreferences(Help.PREFERENCES_NAME, MODE_PRIVATE)).also { help ->
            help.onWelcomeFinished = { completed, card -> telemetry.welcomeFinished(completed, card) }
        }

    @Provides
    @Singleton
    fun resumeState(
        @ApplicationContext context: Context,
    ): ResumeState = ResumeState(context.getSharedPreferences(ResumeState.PREFERENCES_NAME, MODE_PRIVATE))

    @Provides
    @Singleton
    fun galleryAds(
        @ApplicationContext context: Context,
        appSettings: AppSettings,
    ): GalleryAds = GalleryAds(context, allowedByUser = appSettings.galleryAdsThisLaunch)

    @Provides
    @Singleton
    fun reviewPrompt(
        @ApplicationContext context: Context,
    ): ReviewPrompt = ReviewPrompt(context.getSharedPreferences(ReviewPrompt.PREFERENCES_NAME, MODE_PRIVATE))

    @Provides
    @Singleton
    fun feedbackActions(
        @ApplicationContext context: Context,
    ): FeedbackActions = FeedbackActions(context)

    @Provides
    @Singleton
    fun stylusObservationRecorder(): StylusObservationRecorder = StylusObservationRecorder()

    @Provides
    @Singleton
    fun diagnosticsCollector(
        @ApplicationContext context: Context,
        engine: Engine,
    ): DiagnosticsCollector = DiagnosticsCollector(context, engine)

    @Provides
    @Singleton
    fun shapeTraceRecorder(
        @ApplicationContext context: Context,
        @ApplicationScope scope: CoroutineScope,
    ): ShapeTraceRecorder =
        ShapeTraceRecorder(File(context.filesDir, "diagnostics/shape-traces.json"), "${Build.MANUFACTURER} ${Build.MODEL}", scope)

    @Provides
    @Singleton
    fun touchSessionRecorder(
        @ApplicationContext context: Context,
        @ApplicationScope scope: CoroutineScope,
    ): TouchSessionRecorder =
        TouchSessionRecorder(
            File(context.filesDir, "diagnostics/touch-sessions.json"),
            "${Build.MANUFACTURER} ${Build.MODEL}",
            context.resources.displayMetrics.xdpi,
            scope,
        )
}
