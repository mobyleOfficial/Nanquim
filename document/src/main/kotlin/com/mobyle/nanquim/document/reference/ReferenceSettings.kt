package com.mobyle.nanquim.document.reference

import kotlin.math.max
import kotlin.math.min

enum class ReferenceMode(
    val id: String,
) {
    Canvas("canvas"),

    Image("image"),
    ;

    companion object {
        fun fromId(id: String): ReferenceMode? = entries.firstOrNull { it.id == id }
    }
}

enum class ReferenceCorner(
    val id: String,
) {
    TopStart("topStart"),
    TopEnd("topEnd"),
    BottomStart("bottomStart"),
    BottomEnd("bottomEnd"),
    ;

    companion object {
        fun fromId(id: String): ReferenceCorner? = entries.firstOrNull { it.id == id }
    }
}

data class ReferenceView(
    val zoom: Double = 1.0,
    val centerX: Double = 0.5,
    val centerY: Double = 0.5,
) {
    companion object {
        const val MINIMUM_ZOOM = 1.0
        const val MAXIMUM_ZOOM = 32.0
    }
}

data class ReferenceSettings(
    val visible: Boolean = false,
    val mode: ReferenceMode = ReferenceMode.Canvas,
    val x: Double = 1.0,
    val y: Double = 0.0,
    val width: Double = DEFAULT_WIDTH,
    val height: Double = DEFAULT_HEIGHT,
    val docked: ReferenceCorner? = ReferenceCorner.TopEnd,
    val opacity: Float = 1f,
    val canvasView: ReferenceView = ReferenceView(),
    val imageView: ReferenceView = ReferenceView(),
    val hasImage: Boolean = false,
) {
    val view: ReferenceView get() = if (mode == ReferenceMode.Canvas) canvasView else imageView

    fun withView(view: ReferenceView): ReferenceSettings =
        if (mode ==
            ReferenceMode.Canvas
        ) {
            copy(canvasView = view)
        } else {
            copy(imageView = view)
        }

    companion object {
        const val DEFAULT_WIDTH = 320.0
        const val DEFAULT_HEIGHT = 240.0
        const val MINIMUM_SIDE = 120.0
        const val MINIMUM_OPACITY = 0.2f
    }
}

data class ReferenceRect(
    val left: Double,
    val top: Double,
    val width: Double,
    val height: Double,
) {
    val right: Double get() = left + width
    val bottom: Double get() = top + height
}

object ReferenceGeometry {
    const val DOCK_MARGIN = 12.0

    const val DOCK_DISTANCE = 48.0

    fun window(
        settings: ReferenceSettings,
        areaWidth: Double,
        areaHeight: Double,
        density: Double,
    ): ReferenceRect {
        val minimum = ReferenceSettings.MINIMUM_SIDE * density
        val width = (settings.width * density).coerceIn(min(minimum, areaWidth), max(minimum, areaWidth))
        val height = (settings.height * density).coerceIn(min(minimum, areaHeight), max(minimum, areaHeight))
        val margin = DOCK_MARGIN * density
        val roomX = max(0.0, areaWidth - width)
        val roomY = max(0.0, areaHeight - height)
        val (left, top) =
            when (settings.docked) {
                ReferenceCorner.TopStart -> margin to margin
                ReferenceCorner.TopEnd -> roomX - margin to margin
                ReferenceCorner.BottomStart -> margin to roomY - margin
                ReferenceCorner.BottomEnd -> roomX - margin to roomY - margin
                null -> settings.x.coerceIn(0.0, 1.0) * roomX to settings.y.coerceIn(0.0, 1.0) * roomY
            }
        return ReferenceRect(left.coerceIn(0.0, roomX), top.coerceIn(0.0, roomY), width, height)
    }

    fun moved(
        settings: ReferenceSettings,
        left: Double,
        top: Double,
        areaWidth: Double,
        areaHeight: Double,
        density: Double,
    ): ReferenceSettings {
        val current = window(settings, areaWidth, areaHeight, density)
        val roomX = max(0.0, areaWidth - current.width)
        val roomY = max(0.0, areaHeight - current.height)
        val clampedLeft = left.coerceIn(0.0, roomX)
        val clampedTop = top.coerceIn(0.0, roomY)
        val near = (DOCK_DISTANCE + DOCK_MARGIN) * density
        val atStart = clampedLeft <= near
        val atEnd = roomX - clampedLeft <= near
        val atTop = clampedTop <= near
        val atBottom = roomY - clampedTop <= near
        val corner =
            when {
                atTop && atStart -> ReferenceCorner.TopStart
                atTop && atEnd -> ReferenceCorner.TopEnd
                atBottom && atStart -> ReferenceCorner.BottomStart
                atBottom && atEnd -> ReferenceCorner.BottomEnd
                else -> null
            }
        return settings.copy(
            x = if (roomX > 0) clampedLeft / roomX else 0.0,
            y = if (roomY > 0) clampedTop / roomY else 0.0,
            docked = corner,
        )
    }

    fun resized(
        settings: ReferenceSettings,
        width: Double,
        height: Double,
        density: Double,
    ): ReferenceSettings =
        settings.copy(
            width = max(ReferenceSettings.MINIMUM_SIDE, width / density),
            height = max(ReferenceSettings.MINIMUM_SIDE, height / density),
        )

    fun scale(
        view: ReferenceView,
        contentWidth: Double,
        contentHeight: Double,
        windowWidth: Double,
        windowHeight: Double,
    ): Double = min(windowWidth / contentWidth, windowHeight / contentHeight) * view.zoom

    fun contentPoint(
        view: ReferenceView,
        contentWidth: Double,
        contentHeight: Double,
        windowWidth: Double,
        windowHeight: Double,
        x: Double,
        y: Double,
    ): Pair<Double, Double> {
        val scale = scale(view, contentWidth, contentHeight, windowWidth, windowHeight)
        return Pair(
            view.centerX * contentWidth + (x - windowWidth / 2) / scale,
            view.centerY * contentHeight + (y - windowHeight / 2) / scale,
        )
    }

    fun transformed(
        view: ReferenceView,
        contentWidth: Double,
        contentHeight: Double,
        windowWidth: Double,
        windowHeight: Double,
        dx: Double,
        dy: Double,
        factor: Double,
        focusX: Double,
        focusY: Double,
    ): ReferenceView {
        val before = scale(view, contentWidth, contentHeight, windowWidth, windowHeight)
        val zoom = (view.zoom * factor).coerceIn(ReferenceView.MINIMUM_ZOOM, ReferenceView.MAXIMUM_ZOOM)
        val after = before * zoom / view.zoom
        val (focusContentX, focusContentY) = contentPoint(view, contentWidth, contentHeight, windowWidth, windowHeight, focusX, focusY)
        val centerX = focusContentX - (focusX - windowWidth / 2) / after - dx / after
        val centerY = focusContentY - (focusY - windowHeight / 2) / after - dy / after
        return ReferenceView(zoom, (centerX / contentWidth).coerceIn(0.0, 1.0), (centerY / contentHeight).coerceIn(0.0, 1.0))
    }
}
