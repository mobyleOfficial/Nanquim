package com.mobyle.nanquim.diagnostics

import android.app.ActivityManager
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.display.DisplayManager
import android.os.Build
import android.util.DisplayMetrics
import android.view.InputDevice
import android.view.MotionEvent
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import com.mobyle.nanquim.BuildConfig
import com.mobyle.nanquim.engine.Engine
import com.mobyle.nanquim.engine.GpuInfo
import java.time.Instant
import kotlin.coroutines.resume

class DiagnosticsCollector(
    private val context: Context,
    private val engine: Engine,
) {
    suspend fun collect(stylusObservation: StylusObservation): DiagnosticsReport =
        DiagnosticsReport(
            generatedAt = Instant.now().toString(),
            app = AppVersionInfo(BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE),
            device = deviceInfo(),
            gpu = gpuInfo(),
            vulkan = vulkanInfo(),
            displays = displays(),
            stylusDevices = stylusDevices(),
            stylusObservation = stylusObservation,
        )

    private suspend fun gpuInfo(): GpuInfo? =
        withTimeoutOrNull(GPU_INFO_TIMEOUT_MILLISECONDS) {
            suspendCancellableCoroutine { continuation ->
                engine.requestGpuInfo { gpuInfo -> if (continuation.isActive) continuation.resume(gpuInfo) }
            }
        }

    private fun deviceInfo(): DeviceInfo {
        val activityManager = context.getSystemService(ActivityManager::class.java)
        val memoryInfo = ActivityManager.MemoryInfo().also(activityManager::getMemoryInfo)
        val socAvailable = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
        return DeviceInfo(
            manufacturer = Build.MANUFACTURER,
            model = Build.MODEL,
            androidRelease = Build.VERSION.RELEASE,
            sdkInt = Build.VERSION.SDK_INT,
            socManufacturer = if (socAvailable) Build.SOC_MANUFACTURER else null,
            socModel = if (socAvailable) Build.SOC_MODEL else null,
            hardware = Build.HARDWARE,
            board = Build.BOARD,
            totalRamBytes = memoryInfo.totalMem,
            availableRamBytes = memoryInfo.availMem,
            memoryClassMegabytes = activityManager.memoryClass,
            largeMemoryClassMegabytes = activityManager.largeMemoryClass,
        )
    }

    private fun vulkanInfo(): VulkanInfo {
        val features = context.packageManager.systemAvailableFeatures
        val level = features.firstOrNull { it.name == PackageManager.FEATURE_VULKAN_HARDWARE_LEVEL }?.version
        val encodedVersion = features.firstOrNull { it.name == PackageManager.FEATURE_VULKAN_HARDWARE_VERSION }?.version
        return VulkanInfo(hardwareLevel = level, apiVersion = encodedVersion?.let(::decodeVulkanVersion))
    }

    private fun displays(): List<DisplayInfo> =
        context.getSystemService(DisplayManager::class.java).displays.map { display ->
            val currentMode = display.mode
            DisplayInfo(
                displayId = display.displayId,
                name = display.name,
                currentWidthPx = currentMode.physicalWidth,
                currentHeightPx = currentMode.physicalHeight,
                densityDpi =
                    DisplayMetrics()
                        .also {
                            @Suppress("DEPRECATION")
                            display.getRealMetrics(it)
                        }.densityDpi,
                currentRefreshRateHz = currentMode.refreshRate,
                supportedModes =
                    display.supportedModes.map { mode ->
                        DisplayModeInfo(mode.modeId, mode.physicalWidth, mode.physicalHeight, mode.refreshRate)
                    },
            )
        }

    private fun stylusDevices(): List<StylusDeviceInfo> =
        InputDevice
            .getDeviceIds()
            .toList()
            .mapNotNull(InputDevice::getDevice)
            .filter { device ->
                device.supportsSource(InputDevice.SOURCE_STYLUS) || device.supportsSource(InputDevice.SOURCE_BLUETOOTH_STYLUS)
            }.map { device ->
                fun declares(axis: Int) =
                    device.motionRanges.any { range ->
                        range.axis == axis &&
                            (range.isFromSource(InputDevice.SOURCE_STYLUS) || range.isFromSource(InputDevice.SOURCE_TOUCHSCREEN))
                    }
                StylusDeviceInfo(
                    name = device.name,
                    vendorId = device.vendorId,
                    productId = device.productId,
                    isExternal = device.isExternal,
                    declaresPressure = declares(MotionEvent.AXIS_PRESSURE),
                    declaresTilt = declares(MotionEvent.AXIS_TILT),
                    declaresOrientation = declares(MotionEvent.AXIS_ORIENTATION),
                    declaresDistance = declares(MotionEvent.AXIS_DISTANCE),
                )
            }

    private companion object {
        const val GPU_INFO_TIMEOUT_MILLISECONDS = 2_000L

        fun decodeVulkanVersion(encodedVersion: Int): String {
            val major = encodedVersion ushr 22
            val minor = (encodedVersion ushr 12) and 0x3FF
            val patch = encodedVersion and 0xFFF
            return "$major.$minor.$patch"
        }
    }
}
