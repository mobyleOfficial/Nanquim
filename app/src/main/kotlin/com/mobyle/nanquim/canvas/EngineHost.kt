package com.mobyle.nanquim.canvas

import android.app.ActivityManager
import android.content.ComponentCallbacks2
import android.content.Context
import android.widget.Toast
import com.mobyle.nanquim.R
import com.mobyle.nanquim.di.ApplicationScope
import com.mobyle.nanquim.engine.Engine
import com.mobyle.nanquim.engine.EngineConfig
import com.mobyle.nanquim.engine.Renderer
import com.mobyle.nanquim.settings.AppSettings
import com.mobyle.nanquim.telemetry.Telemetry
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Starts the native engine the first time something asks for it, never before: opening the app must not pay for it
 * until a screen needs it. Also keeps its memory budget in step with the memory the system has left.
 */
@Singleton
class EngineHost
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
        private val appSettings: AppSettings,
        private val telemetry: Telemetry,
        private val memoryBudgetPolicy: MemoryBudgetPolicy,
        @param:ApplicationScope private val scope: CoroutineScope,
    ) {
        @Volatile
        private var created = false

        val engine: Engine by lazy {
            created = true
            startMemoryMonitor()
            val renderer = developerRenderer() ?: appSettings.renderer
            appSettings.startedRenderer = renderer
            telemetry.memoryBudgetChanged(memoryBudgetPolicy.currentBytes)
            reportInterruptedShaderCompile()
            Engine(EngineConfig(memoryBudgetPolicy.currentBytes, scratchDirectory().absolutePath, renderer = renderer)).also { engine ->
                scope.launch { engine.unrecoverableError.filterNotNull().collect(telemetry::engineFailed) }
                engine.requestGpuInfo { info ->
                    scope.launch {
                        val active = if (info.api == "vulkan") Renderer.Vulkan else Renderer.Gles
                        appSettings.activeRenderer = active
                        telemetry.rendererChanged(AppSettings.rendererId(renderer), AppSettings.rendererId(active))
                        if (active != renderer) {
                            Toast.makeText(context, R.string.renderer_fallback_notice, Toast.LENGTH_LONG).show()
                        }
                    }
                }
            }
        }

        fun onTrimMemory(level: Int) {
            if (created && level >= ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN) {
                memoryBudgetPolicy.onBackground()?.let(::applyMemoryBudget)
            }
        }

        private fun startMemoryMonitor() {
            scope.launch {
                while (true) {
                    delay(MEMORY_SAMPLE_INTERVAL_MILLISECONDS)
                    val info = context.memoryInfo()
                    memoryBudgetPolicy.onMemorySample(info.availMem, info.threshold, info.lowMemory)?.let(::applyMemoryBudget)
                }
            }
        }

        private fun applyMemoryBudget(bytes: Long) {
            engine.setMemoryBudget(bytes)
            telemetry.memoryBudgetChanged(bytes)
        }

        private fun scratchDirectory(): File = File(context.cacheDir, "engine").apply { mkdirs() }

        /**
         * The engine leaves this marker behind when the GPU driver crashes the process while it
         * compiles a shader; report which program it was, so the native crash can be traced to it.
         */
        private fun reportInterruptedShaderCompile() {
            val marker = File(scratchDirectory(), "shaders/$SHADER_COMPILING_MARKER")
            val lines = runCatching { marker.readLines() }.getOrNull() ?: return
            marker.delete()
            telemetry.shaderCompileInterrupted(program = lines.getOrNull(0).orEmpty(), driver = lines.getOrNull(1).orEmpty())
        }

        private fun developerRenderer(): Renderer? {
            val value =
                runCatching {
                    @Suppress("PrivateApi")
                    Class.forName("android.os.SystemProperties").getMethod("get", String::class.java).invoke(null, "debug.nanquim.renderer")
                }.getOrNull() as? String
            return AppSettings.rendererFromId(value)
        }

        private companion object {
            /** Kept in sync with ShaderVariantCache::kCompilingMarker in the engine. */
            const val SHADER_COMPILING_MARKER = "compiling.txt"
            const val MEMORY_SAMPLE_INTERVAL_MILLISECONDS = 2_000L
        }
    }

fun Context.memoryInfo(): ActivityManager.MemoryInfo =
    ActivityManager.MemoryInfo().also { getSystemService(ActivityManager::class.java).getMemoryInfo(it) }
