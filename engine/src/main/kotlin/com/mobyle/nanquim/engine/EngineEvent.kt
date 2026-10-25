package com.mobyle.nanquim.engine

sealed interface EngineEvent {
    data class TransformReady(
        val requestId: Int,
        val x: Int,
        val y: Int,
        val width: Int,
        val height: Int,
        val centroidX: Double,
        val centroidY: Double,
        val principalAngle: Double,
    ) : EngineEvent

    data class HistoryEntryReady(
        val entry: Long,
        val bytes: Long,
    ) : EngineEvent

    data class DocumentSaved(
        val result: DocumentSaveResult,
    ) : EngineEvent

    data class DocumentTilesCorrupt(
        val count: Long,
    ) : EngineEvent

    data object DocumentShown : EngineEvent

    data class Error(
        val code: EngineErrorCode,
        val message: String,
        val recoverable: Boolean,
    ) : EngineEvent
}

enum class EngineErrorCode(
    val nativeValue: Int,
) {
    Unknown(0),
    EglInitializationFailed(1),
    GlesVersionUnsupported(2),
    WindowSurfaceFailed(3),
    DocumentOpenFailed(4),
    RendererInitializationFailed(5),

    InvalidReport(-1),
    ;

    companion object {
        fun fromNative(nativeValue: Int): EngineErrorCode = entries.firstOrNull { it.nativeValue == nativeValue } ?: Unknown
    }
}
