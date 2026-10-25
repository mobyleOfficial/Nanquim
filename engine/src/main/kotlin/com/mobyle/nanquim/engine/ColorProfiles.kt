package com.mobyle.nanquim.engine

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

enum class ProfileColorSpace {
    Rgb,
    Gray,
    Cmyk,
    Other,
}

data class ColorProfileInfo(
    val description: String,
    val colorSpace: ProfileColorSpace,
    val convertsFrom: Boolean,
    val convertsTo: Boolean,
    val majorVersion: Int,
) {
    val isDocumentProfile: Boolean get() = colorSpace == ProfileColorSpace.Rgb && convertsFrom && convertsTo
}

sealed interface ColorProfileReadResult {
    data class Success(
        val info: ColorProfileInfo,
    ) : ColorProfileReadResult

    data class Invalid(
        val reason: String,
    ) : ColorProfileReadResult
}

object ColorProfiles {
    init {
        System.loadLibrary("nanquim_engine")
    }

    const val MAXIMUM_BYTES = 16 * 1024 * 1024

    fun read(bytes: ByteArray): ColorProfileReadResult {
        if (bytes.size > MAXIMUM_BYTES) return ColorProfileReadResult.Invalid("The profile is larger than 16 MB")
        val fields = Json.parseToJsonElement(nativeRead(bytes)).jsonObject
        fields["error"]?.let { return ColorProfileReadResult.Invalid(it.jsonPrimitive.content) }
        return ColorProfileReadResult.Success(
            ColorProfileInfo(
                description = fields.getValue("description").jsonPrimitive.content,
                colorSpace =
                    when (fields.getValue("colorSpace").jsonPrimitive.content) {
                        "rgb" -> ProfileColorSpace.Rgb
                        "gray" -> ProfileColorSpace.Gray
                        "cmyk" -> ProfileColorSpace.Cmyk
                        else -> ProfileColorSpace.Other
                    },
                convertsFrom = fields.getValue("convertsFrom").jsonPrimitive.boolean,
                convertsTo = fields.getValue("convertsTo").jsonPrimitive.boolean,
                majorVersion = fields.getValue("version").jsonPrimitive.int,
            ),
        )
    }

    fun convertRgba8(
        pixels: ByteArray,
        source: ByteArray?,
        destination: ByteArray?,
        premultiplied: Boolean,
    ): String? = nativeConvertRgba8(pixels, source, destination, premultiplied)

    @JvmStatic
    private external fun nativeRead(profile: ByteArray): String

    @JvmStatic
    private external fun nativeConvertRgba8(
        pixels: ByteArray,
        source: ByteArray?,
        destination: ByteArray?,
        premultiplied: Boolean,
    ): String?
}
