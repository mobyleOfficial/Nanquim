package com.mobyle.nanquim.engine

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class InterchangeImportResult(
    val ok: Boolean,
    val errorCode: String = "",
    val errorDetail: String = "",
)

class CmykImportProfiles(
    val fallback: ByteArray? = null,
    val working: ByteArray? = null,
)

@Serializable
data class InterchangeWarning(
    val code: String,
    val detail: String = "",
)

@Serializable
data class InterchangeExportResult(
    val ok: Boolean,
    val errorCode: String = "",
    val errorDetail: String = "",
    val warnings: List<InterchangeWarning> = emptyList(),
)

object InterchangeFiles {
    init {
        System.loadLibrary("nanquim_engine")
    }

    private val json = Json { ignoreUnknownKeys = true }

    fun importPsd(
        sourcePath: String,
        folder: String,
        maximumSide: Int,
        maximumLayers: Int,
        cmyk: CmykImportProfiles = CmykImportProfiles(),
    ): InterchangeImportResult =
        json.decodeFromString(
            InterchangeImportResult.serializer(),
            nativeImportPsd(sourcePath, folder, maximumSide, maximumLayers, cmyk.fallback, cmyk.working),
        )

    fun exportPsd(
        documentFolder: String,
        layerTree: String,
        backgroundLayerName: String,
        composite: ByteArray,
        width: Int,
        height: Int,
        outputPath: String,
        deepComposite: Boolean = false,
        iccProfile: ByteArray? = null,
        cmykProfile: ByteArray? = null,
    ): InterchangeExportResult =
        json.decodeFromString(
            InterchangeExportResult.serializer(),
            nativeExportPsd(
                documentFolder,
                layerTree,
                backgroundLayerName,
                composite,
                width,
                height,
                deepComposite,
                iccProfile,
                cmykProfile,
                outputPath,
            ),
        )

    fun importTiff(
        sourcePath: String,
        folder: String,
        maximumSide: Int,
        cmyk: CmykImportProfiles = CmykImportProfiles(),
    ): InterchangeImportResult =
        json.decodeFromString(
            InterchangeImportResult.serializer(),
            nativeImportTiff(sourcePath, folder, maximumSide, cmyk.fallback, cmyk.working),
        )

    fun exportTiff(
        composite: ByteArray,
        width: Int,
        height: Int,
        dpi: Double,
        outputPath: String,
        deepComposite: Boolean = false,
        iccProfile: ByteArray? = null,
        cmykProfile: ByteArray? = null,
    ): InterchangeExportResult =
        json.decodeFromString(
            InterchangeExportResult.serializer(),
            nativeExportTiff(composite, width, height, dpi, deepComposite, iccProfile, cmykProfile, outputPath),
        )

    fun exportPng(
        composite: ByteArray,
        width: Int,
        height: Int,
        dpi: Double,
        outputPath: String,
        deepComposite: Boolean = false,
        iccProfile: ByteArray? = null,
    ): InterchangeExportResult =
        json.decodeFromString(
            InterchangeExportResult.serializer(),
            nativeExportPng(composite, width, height, dpi, deepComposite, iccProfile, outputPath),
        )

    fun exportPdf(
        documentFolder: String,
        layerTree: String,
        composite: ByteArray,
        width: Int,
        height: Int,
        layersAsPages: Boolean,
        jpeg: Boolean,
        jpegQuality: Int,
        title: String,
        outputPath: String,
        deepComposite: Boolean = false,
        iccProfile: ByteArray? = null,
        cmykProfile: ByteArray? = null,
    ): InterchangeExportResult =
        json.decodeFromString(
            InterchangeExportResult.serializer(),
            nativeExportPdf(
                documentFolder,
                layerTree,
                composite,
                width,
                height,
                deepComposite,
                layersAsPages,
                jpeg,
                jpegQuality,
                title,
                iccProfile,
                cmykProfile,
                outputPath,
            ),
        )

    @JvmStatic
    private external fun nativeExportPdf(
        documentFolder: String,
        layerTree: String,
        composite: ByteArray,
        width: Int,
        height: Int,
        deepComposite: Boolean,
        layersAsPages: Boolean,
        jpeg: Boolean,
        jpegQuality: Int,
        title: String,
        iccProfile: ByteArray?,
        cmykProfile: ByteArray?,
        outputPath: String,
    ): String

    @JvmStatic
    private external fun nativeExportPng(
        composite: ByteArray,
        width: Int,
        height: Int,
        dpi: Double,
        deepComposite: Boolean,
        iccProfile: ByteArray?,
        outputPath: String,
    ): String

    fun importProcreate(
        sourcePath: String,
        folder: String,
        maximumSide: Int,
        maximumLayers: Int,
    ): InterchangeImportResult =
        json.decodeFromString(
            InterchangeImportResult.serializer(),
            nativeImportProcreate(sourcePath, folder, maximumSide, maximumLayers),
        )

    fun readLayer(
        surfaceFolder: String,
        width: Int,
        height: Int,
    ): ByteArray? = nativeReadLayer(surfaceFolder, width, height)

    fun keyedArchiveJson(archive: ByteArray): String? = nativeKeyedArchiveJson(archive)

    @JvmStatic
    private external fun nativeKeyedArchiveJson(archive: ByteArray): String?

    fun paletteJson(
        bytes: ByteArray,
        cmykProfile: ByteArray? = null,
    ): String? = nativePaletteJson(bytes, cmykProfile)

    @JvmStatic
    private external fun nativePaletteJson(
        palette: ByteArray,
        cmykProfile: ByteArray?,
    ): String?

    @JvmStatic
    private external fun nativeReadLayer(
        surfaceFolder: String,
        width: Int,
        height: Int,
    ): ByteArray?

    @JvmStatic
    private external fun nativeImportProcreate(
        sourcePath: String,
        folder: String,
        maximumSide: Int,
        maximumLayers: Int,
    ): String

    @JvmStatic
    private external fun nativeImportTiff(
        sourcePath: String,
        folder: String,
        maximumSide: Int,
        cmykFallbackProfile: ByteArray?,
        cmykWorkingProfile: ByteArray?,
    ): String

    @JvmStatic
    private external fun nativeExportTiff(
        composite: ByteArray,
        width: Int,
        height: Int,
        dpi: Double,
        deepComposite: Boolean,
        iccProfile: ByteArray?,
        cmykProfile: ByteArray?,
        outputPath: String,
    ): String

    @JvmStatic
    private external fun nativeExportPsd(
        documentFolder: String,
        layerTree: String,
        backgroundLayerName: String,
        composite: ByteArray,
        width: Int,
        height: Int,
        deepComposite: Boolean,
        iccProfile: ByteArray?,
        cmykProfile: ByteArray?,
        outputPath: String,
    ): String

    @JvmStatic
    private external fun nativeImportPsd(
        sourcePath: String,
        folder: String,
        maximumSide: Int,
        maximumLayers: Int,
        cmykFallbackProfile: ByteArray?,
        cmykWorkingProfile: ByteArray?,
    ): String
}
