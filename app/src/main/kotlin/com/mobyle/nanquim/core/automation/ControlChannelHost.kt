package com.mobyle.nanquim.core.automation

import android.content.Context
import android.content.res.Configuration
import com.mobyle.nanquim.BuildConfig
import com.mobyle.nanquim.data.help.Help
import com.mobyle.nanquim.domain.canvas.CanvasSession
import com.mobyle.nanquim.domain.command.CommandDispatcher
import com.mobyle.nanquim.domain.settings.repositories.AppSettingsRepository
import com.mobyle.nanquim.engine.Engine
import com.mobyle.nanquim.presentation.common.getString
import kotlinx.coroutines.CoroutineScope
import java.util.Locale

/** Owns the developer builds' automation channel, started once per process by the first activity. */
class ControlChannelHost(
    private val context: Context,
    private val dispatcher: Lazy<CommandDispatcher>,
    private val canvasSession: Lazy<CanvasSession>,
    private val engine: Lazy<Engine>,
    private val appSettings: Lazy<AppSettingsRepository>,
    private val help: Lazy<Help>,
    private val scope: CoroutineScope,
) {
    var channel: ControlChannel? = null
        private set

    fun start(controlToken: String?) {
        if (!BuildConfig.DEVELOPER_TOOLS || channel != null) return
        if (controlToken != null) help.value.automatic = false
        val dispatcher = dispatcher.value
        val host = CanvasAutomationHost(canvasSession.value, engine.value, scope, appSettings.value, help.value)
        val english = context.createConfigurationContext(Configuration(context.resources.configuration).apply { setLocale(Locale.ENGLISH) })
        channel =
            ControlChannel(
                registry = dispatcher.registry,
                context = dispatcher.context,
                host = host,
                token = controlToken ?: ControlChannel.newToken(),
                title = { key -> runCatching { english.resources.getString(key) }.getOrDefault("") },
            ).also { it.start() }
    }
}
