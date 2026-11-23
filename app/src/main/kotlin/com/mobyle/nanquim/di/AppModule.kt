package com.mobyle.nanquim.di

import android.content.Context
import android.content.Context.MODE_PRIVATE
import android.os.Build
import com.mobyle.nanquim.BuildConfig
import com.mobyle.nanquim.core.automation.ControlChannelHost
import com.mobyle.nanquim.core.navigation.AppNavigator
import com.mobyle.nanquim.core.telemetry.Telemetry
import com.mobyle.nanquim.data.ads.GalleryAds
import com.mobyle.nanquim.data.canvas.ResumeState
import com.mobyle.nanquim.data.diagnostics.DiagnosticsCollector
import com.mobyle.nanquim.data.diagnostics.ShapeTraceRecorder
import com.mobyle.nanquim.data.diagnostics.TouchSessionRecorder
import com.mobyle.nanquim.data.feedback.AppDetails
import com.mobyle.nanquim.data.feedback.FeedbackActions
import com.mobyle.nanquim.data.feedback.ReviewPrompt
import com.mobyle.nanquim.data.help.Help
import com.mobyle.nanquim.data.macro.Macros
import com.mobyle.nanquim.domain.diagnostics.StylusObservationRecorder
import com.mobyle.nanquim.domain.settings.repositories.AppSettingsRepository
import com.mobyle.nanquim.presentation.shell.LaunchState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.dsl.singleOf
import org.koin.dsl.module
import java.io.File

/** Settings, navigation, telemetry and the other application-wide objects that do not need the engine. */
val appModule =
    module {
        single(ApplicationScope) { CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate) }

        single {
            Telemetry.create(
                androidContext(),
                analyticsEnabled = BuildConfig.BUILD_TYPE != "benchmark",
                userAllows = get<AppSettingsRepository>().usageStatistics,
            )
        }

        singleOf(::AppNavigator)

        singleOf(::LaunchState)

        single { Macros(File(androidContext().filesDir, Macros.FOLDER)) }

        single {
            val telemetry = get<Telemetry>()
            Help(androidContext().getSharedPreferences(Help.PREFERENCES_NAME, MODE_PRIVATE)).also { help ->
                help.onWelcomeFinished = { completed, card -> telemetry.welcomeFinished(completed, card) }
            }
        }

        single { ResumeState(androidContext().getSharedPreferences(ResumeState.PREFERENCES_NAME, MODE_PRIVATE)) }

        single { GalleryAds(androidContext(), allowedByUser = get<AppSettingsRepository>().galleryAdsThisLaunch) }

        single { ReviewPrompt(androidContext().getSharedPreferences(ReviewPrompt.PREFERENCES_NAME, MODE_PRIVATE)) }

        single { FeedbackActions(androidContext()) }

        single { AppDetails(androidContext(), inject()) }

        singleOf(::StylusObservationRecorder)

        single { DiagnosticsCollector(androidContext(), get()) }

        single {
            ShapeTraceRecorder(
                File(androidContext().filesDir, "diagnostics/shape-traces.json"),
                deviceName(),
                get(ApplicationScope),
            )
        }

        single {
            val context = androidContext()
            TouchSessionRecorder(
                File(context.filesDir, "diagnostics/touch-sessions.json"),
                deviceName(),
                context.resources.displayMetrics.xdpi,
                get(ApplicationScope),
            )
        }

        single {
            ControlChannelHost(
                context = get<Context>(),
                dispatcher = inject(),
                canvasSession = inject(),
                engine = inject(),
                appSettings = inject(),
                help = inject(),
                scope = get(ApplicationScope),
            )
        }
    }

private fun deviceName() = "${Build.MANUFACTURER} ${Build.MODEL}"
