package com.mobyle.nanquim.document.layer

import kotlin.math.roundToInt

enum class CanvasAnchor(
    val horizontal: Int,
    val vertical: Int,
) {
    TopLeft(0, 0),
    Top(1, 0),
    TopRight(2, 0),
    Left(0, 1),
    Center(1, 1),
    Right(2, 1),
    BottomLeft(0, 2),
    Bottom(1, 2),
    BottomRight(2, 2),
}

sealed interface CanvasOperation {
    data class Crop(
        val x: Int,
        val y: Int,
        val width: Int,
        val height: Int,
    ) : CanvasOperation

    data class Resize(
        val width: Int,
        val height: Int,
        val anchor: CanvasAnchor = CanvasAnchor.Center,
        val resample: Boolean = false,
    ) : CanvasOperation

    data class Rotate(
        val quarterTurns: Int,
    ) : CanvasOperation

    data class Flip(
        val horizontal: Boolean,
    ) : CanvasOperation

    data class Mapping(
        val width: Int,
        val height: Int,
        val matrix: DoubleArray,
        val resample: Boolean,
    ) {
        fun mesh(
            oldWidth: Int,
            oldHeight: Int,
        ): FloatArray {
            val corners =
                listOf(0.0 to 0.0, oldWidth.toDouble() to 0.0, oldWidth.toDouble() to oldHeight.toDouble(), 0.0 to oldHeight.toDouble())
            val order = intArrayOf(0, 1, 2, 0, 2, 3)
            val mesh = FloatArray(order.size * 5)
            order.forEachIndexed { index, corner ->
                val (u, v) = corners[corner]
                mesh[index * 5] = (matrix[0] * u + matrix[1] * v + matrix[2]).toFloat()
                mesh[index * 5 + 1] = (matrix[3] * u + matrix[4] * v + matrix[5]).toFloat()
                mesh[index * 5 + 2] = 1f
                mesh[index * 5 + 3] = u.toFloat()
                mesh[index * 5 + 4] = v.toFloat()
            }
            return mesh
        }

        override fun equals(other: Any?) =
            other is Mapping && width == other.width && height == other.height && matrix.contentEquals(other.matrix) &&
                resample == other.resample

        override fun hashCode() = (width * 31 + height) * 31 + matrix.contentHashCode()
    }

    fun mapping(
        oldWidth: Int,
        oldHeight: Int,
    ): Mapping =
        when (this) {
            is Crop -> Mapping(width, height, doubleArrayOf(1.0, 0.0, -x.toDouble(), 0.0, 1.0, -y.toDouble()), false)
            is Resize ->
                if (resample) {
                    Mapping(
                        width,
                        height,
                        doubleArrayOf(width.toDouble() / oldWidth, 0.0, 0.0, 0.0, height.toDouble() / oldHeight, 0.0),
                        true,
                    )
                } else {
                    val dx = ((width - oldWidth) * anchor.horizontal / 2.0).roundToInt()
                    val dy = ((height - oldHeight) * anchor.vertical / 2.0).roundToInt()
                    Mapping(width, height, doubleArrayOf(1.0, 0.0, dx.toDouble(), 0.0, 1.0, dy.toDouble()), false)
                }
            is Rotate ->
                when (Math.floorMod(quarterTurns, 4)) {
                    1 -> Mapping(oldHeight, oldWidth, doubleArrayOf(0.0, -1.0, oldHeight.toDouble(), 1.0, 0.0, 0.0), false)
                    2 -> Mapping(oldWidth, oldHeight, doubleArrayOf(-1.0, 0.0, oldWidth.toDouble(), 0.0, -1.0, oldHeight.toDouble()), false)
                    3 -> Mapping(oldHeight, oldWidth, doubleArrayOf(0.0, 1.0, 0.0, -1.0, 0.0, oldWidth.toDouble()), false)
                    else -> Mapping(oldWidth, oldHeight, doubleArrayOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0), false)
                }
            is Flip ->
                if (horizontal) {
                    Mapping(oldWidth, oldHeight, doubleArrayOf(-1.0, 0.0, oldWidth.toDouble(), 0.0, 1.0, 0.0), false)
                } else {
                    Mapping(oldWidth, oldHeight, doubleArrayOf(1.0, 0.0, 0.0, 0.0, -1.0, oldHeight.toDouble()), false)
                }
        }
}
