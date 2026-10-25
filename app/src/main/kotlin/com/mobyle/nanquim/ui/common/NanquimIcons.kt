package com.mobyle.nanquim.ui.common

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

object NanquimIcons {
    val Actions: ImageVector by lazy {
        icon("Actions") {
            stroke {
                moveTo(19.76f, 6.63f)
                arcTo(4f, 4f, 0f, isMoreThanHalf = true, isPositiveArc = true, x1 = 17.37f, y1 = 4.24f)
                moveTo(13.2f, 10.8f)
                lineTo(5f, 19f)
            }
        }
    }

    val Adjustments: ImageVector by lazy {
        icon("Adjustments") {
            stroke {
                moveTo(4f, 20f)
                lineTo(14f, 10f)
            }
            stroke(width = 1.6f) {
                moveTo(18f, 3f)
                lineTo(18f, 9f)
                moveTo(15f, 6f)
                lineTo(21f, 6f)
            }
        }
    }

    val Selection: ImageVector by lazy {
        icon("Selection") {
            stroke {
                moveTo(17f, 6f)
                curveTo(13f, 3f, 6f, 5f, 8f, 9.5f)
                curveTo(10f, 13.5f, 17f, 11.5f, 16f, 16.5f)
                curveTo(15f, 20.5f, 9f, 20.5f, 7f, 18f)
            }
        }
    }

    val Transform: ImageVector by lazy {
        icon("Transform") {
            fill {
                moveTo(6f, 3.5f)
                lineTo(18.5f, 12f)
                lineTo(13f, 13f)
                lineTo(16f, 19.5f)
                lineTo(13.6f, 20.6f)
                lineTo(10.7f, 14f)
                lineTo(6f, 18f)
                close()
            }
        }
    }

    val Brush: ImageVector by lazy {
        icon("Brush") {
            stroke {
                moveTo(19.5f, 4.5f)
                lineTo(11.5f, 12.5f)
            }
            fill {
                moveTo(11.8f, 12.2f)
                curveTo(9f, 10.8f, 6f, 13f, 6.6f, 16f)
                curveTo(6.9f, 18f, 5.2f, 19.4f, 4f, 20f)
                curveTo(8f, 20.6f, 13f, 18f, 11.8f, 12.2f)
                close()
            }
        }
    }

    val Smudge: ImageVector by lazy {
        icon("Smudge") {
            stroke {
                moveTo(9.5f, 14.5f)
                lineTo(14.2f, 4.6f)
                arcTo(2.2f, 2.2f, 0f, isMoreThanHalf = false, isPositiveArc = true, x1 = 18.2f, y1 = 6.5f)
                lineTo(13.5f, 16.4f)
                close()
                moveTo(3.5f, 20f)
                curveTo(5.5f, 18f, 7.5f, 21f, 9.5f, 19f)
                curveTo(11f, 17.5f, 12.5f, 18.8f, 14f, 17.6f)
            }
        }
    }

    val Eraser: ImageVector by lazy {
        icon("Eraser") {
            stroke {
                moveTo(4.5f, 15f)
                lineTo(13f, 6.5f)
                lineTo(18.5f, 12f)
                lineTo(10.5f, 20f)
                lineTo(9.5f, 20f)
                close()
                moveTo(8f, 11.5f)
                lineTo(13.5f, 17f)
                moveTo(10.5f, 20f)
                lineTo(19.5f, 20f)
            }
        }
    }

    val Layers: ImageVector by lazy {
        icon("Layers") {
            stroke {
                moveTo(12f, 3.5f)
                lineTo(20f, 8f)
                lineTo(12f, 12.5f)
                lineTo(4f, 8f)
                close()
                moveTo(4f, 12f)
                lineTo(12f, 16.5f)
                lineTo(20f, 12f)
                moveTo(4f, 16f)
                lineTo(12f, 20.5f)
                lineTo(20f, 16f)
            }
        }
    }

    val Gallery: ImageVector by lazy {
        icon("Gallery") {
            stroke {
                for ((left, top) in listOf(4f to 4f, 13f to 4f, 4f to 13f, 13f to 13f)) {
                    moveTo(left + 1f, top)
                    lineTo(left + 6f, top)
                    lineTo(left + 7f, top + 1f)
                    lineTo(left + 7f, top + 6f)
                    lineTo(left + 6f, top + 7f)
                    lineTo(left + 1f, top + 7f)
                    lineTo(left, top + 6f)
                    lineTo(left, top + 1f)
                    close()
                }
            }
        }
    }

    private class Icon(
        val builder: ImageVector.Builder,
    ) {
        fun stroke(
            width: Float = 2f,
            path: PathBuilder.() -> Unit,
        ) {
            builder.path(
                stroke = SolidColor(Color.Black),
                strokeLineWidth = width,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
                pathBuilder = path,
            )
        }

        fun fill(path: PathBuilder.() -> Unit) {
            builder.path(fill = SolidColor(Color.Black), pathBuilder = path)
        }
    }

    private fun icon(
        name: String,
        content: Icon.() -> Unit,
    ): ImageVector {
        val builder = ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f)
        Icon(builder).content()
        return builder.build()
    }
}
