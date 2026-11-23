package com.mobyle.nanquim.core.automation

import android.content.Context
import android.content.res.Configuration
import com.mobyle.nanquim.BuildConfig
import com.mobyle.nanquim.data.help.Help
import com.mobyle.nanquim.di.ApplicationScope
import com.mobyle.nanquim.domain.canvas.CanvasSession
import com.mobyle.nanquim.domain.command.CommandDispatcher
import com.mobyle.nanquim.domain.settings.repositories.AppSettingsRepository
import com.mobyle.nanquim.engine.Engine
import dagger.Lazy
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/** Owns the developer builds' automation channel, started once per process by the first activity. */
@Singleton
class ControlChannelHost
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
        private val dispatcher: Lazy<CommandDispatcher>,
        private val canvasSession: Lazy<CanvasSession>,
        private val engine: Lazy<Engine>,
        private val appSettings: Lazy<AppSettingsRepository>,
        private val help: Lazy<Help>,
        @param:ApplicationScope private val scope: CoroutineScope,
    ) {
        var channel: ControlChannel? = null
            private set

        fun start(controlToken: String?) {
            if (!BuildConfig.DEVELOPER_TOOLS || channel != null) return
            if (controlToken != null) help.get().automatic = false
            val dispatcher = dispatcher.get()
            val host = CanvasAutomationHost(canvasSession.get(), engine.get(), scope, appSettings.get(), help.get())
            val english = context.createConfigurationContext(Configuration(context.resources.configuration).apply { setLocale(Locale.ENGLISH) })
            channel =
                ControlChannel(
                    registry = dispatcher.registry,
                    context = dispatcher.context,
                    host = host,
                    token = controlToken ?: ControlChannel.newToken(),
                    title = { titleRes -> runCatching { english.getString(titleRes) }.getOrDefault("") },
                ).also { it.start() }
        }
    }
