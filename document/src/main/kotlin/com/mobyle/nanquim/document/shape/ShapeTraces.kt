package com.mobyle.nanquim.document.shape

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class ShapeTrace(
    val label: String,
    val recordedBy: String = "",
    val points: List<List<Double>>,
) {
    fun shapePoints(): List<ShapePoint> = points.map { ShapePoint(it[0], it[1]) }
}

@Serializable
data class ShapeTraceSet(
    val version: Int = VERSION,
    val device: String = "",
    val traces: List<ShapeTrace> = emptyList(),
) {
    companion object {
        const val VERSION = 1

        val LABELS = listOf("line", "arc", "ellipse", "rectangle", "polygon", "curve")

        private val json =
            Json {
                ignoreUnknownKeys = true
                encodeDefaults = true
            }

        fun decode(text: String): ShapeTraceSet = json.decodeFromString(serializer(), text)

        fun encode(set: ShapeTraceSet): String = json.encodeToString(serializer(), set)

        fun labelOf(kind: ShapeKind?): String? =
            when (kind) {
                null -> null
                ShapeKind.Circle -> ShapeKind.Ellipse.label
                else -> kind.label
            }
    }
}
