package com.mobyle.nanquim.engine

import kotlinx.serialization.Serializable

@Serializable
data class GpuInfo(
    val api: String = "gles",
    val vendor: String,
    val renderer: String,
    val version: String,
    val versionMajor: Int,
    val versionMinor: Int,
    val shadingLanguageVersion: String,
    val maxTextureSize: Int,
    val maxRenderbufferSize: Int,
    val supportsColorBufferHalfFloat: Boolean,
    val programBinaryFormats: List<Int>,
    val extensions: List<String>,
    val eglVersion: String,
    val eglVendor: String,
    val eglExtensions: List<String>,
)
