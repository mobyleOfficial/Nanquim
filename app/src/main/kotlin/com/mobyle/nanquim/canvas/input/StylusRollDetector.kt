package com.mobyle.nanquim.canvas.input

import kotlin.math.PI
import kotlin.math.abs

class StylusRollDetector {
    private var lastUprightOrientation: Float? = null

    fun observe(
        tilt: Float,
        orientation: Float,
    ): Boolean {
        if (abs(tilt) > UPRIGHT_TILT) {
            lastUprightOrientation = null
            return false
        }
        val last = lastUprightOrientation
        lastUprightOrientation = orientation
        if (last == null) return false
        var turn = abs(orientation - last) % FULL_TURN
        if (turn > PI) turn = FULL_TURN - turn
        return turn >= MINIMUM_TURN
    }

    fun reset() {
        lastUprightOrientation = null
    }

    private companion object {
        const val UPRIGHT_TILT = 0.001f
        const val MINIMUM_TURN = 0.2f
        const val FULL_TURN = (2 * PI).toFloat()
    }
}
