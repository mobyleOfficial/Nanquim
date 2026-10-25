package com.mobyle.nanquim.color

import com.mobyle.nanquim.document.color.CmykConversion
import com.mobyle.nanquim.document.color.CmykInks
import com.mobyle.nanquim.document.color.ColorValue
import com.mobyle.nanquim.engine.CmykColors

class ProfileCmykConversion(
    private val colors: CmykColors,
) : CmykConversion,
    AutoCloseable by colors {
    override fun inksOf(color: ColorValue): CmykInks {
        val inks = colors.toCmyk(color.red, color.green, color.blue)
        return CmykInks(inks[0], inks[1], inks[2], inks[3])
    }

    override fun colorOf(
        inks: CmykInks,
        alpha: Float,
        previous: ColorValue?,
    ): ColorValue {
        val rgb = colors.toRgb(inks.cyan, inks.magenta, inks.yellow, inks.black)
        return ColorValue.fromRgb(rgb[0], rgb[1], rgb[2], alpha, previous)
    }
}
