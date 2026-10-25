package com.mobyle.nanquim.diagnostics

import kotlinx.serialization.Serializable
import com.mobyle.nanquim.engine.GpuInfo

@Serializable
data class DiagnosticsReport(
    val generatedAt: String,
    val app: AppVersionInfo,
    val device: DeviceInfo,
    val gpu: GpuInfo?,
    val vulkan: VulkanInfo,
    val displays: List<DisplayInfo>,
    val stylusDevices: List<StylusDeviceInfo>,
    val stylusObservation: StylusObservation,
)

@Serializable
data class AppVersionInfo(
    val versionName: String,
    val versionCode: Int,
)

@Serializable
data class DeviceInfo(
    val manufacturer: String,
    val model: String,
    val androidRelease: String,
    val sdkInt: Int,
    val socManufacturer: String?,
    val socModel: String?,
    val hardware: String,
    val board: String,
    val totalRamBytes: Long,
    val availableRamBytes: Long,
    val memoryClassMegabytes: Int,
    val largeMemoryClassMegabytes: Int,
)

@Serializable
data class VulkanInfo(
    val hardwareLevel: Int?,
    val apiVersion: String?,
)

@Serializable
data class DisplayInfo(
    val displayId: Int,
    val name: String,
    val currentWidthPx: Int,
    val currentHeightPx: Int,
    val densityDpi: Int,
    val currentRefreshRateHz: Float,
    val supportedModes: List<DisplayModeInfo>,
)

@Serializable
data class DisplayModeInfo(
    val modeId: Int,
    val widthPx: Int,
    val heightPx: Int,
    val refreshRateHz: Float,
)

@Serializable
data class StylusDeviceInfo(
    val name: String,
    val vendorId: Int,
    val productId: Int,
    val isExternal: Boolean,
    val declaresPressure: Boolean,
    val declaresTilt: Boolean,
    val declaresOrientation: Boolean,
    val declaresDistance: Boolean,
)
