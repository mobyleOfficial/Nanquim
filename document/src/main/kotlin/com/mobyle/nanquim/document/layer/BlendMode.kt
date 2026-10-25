package com.mobyle.nanquim.document.layer

enum class BlendMode(
    val id: String,
    val engineCode: Int,
    val abbreviation: String,
    val separable: Boolean = true,
) {
    Normal("normal", 0, "N"),
    Multiply("multiply", 1, "M"),
    Screen("screen", 2, "S"),
    Overlay("overlay", 3, "O"),
    Darken("darken", 4, "D"),
    Lighten("lighten", 5, "L"),
    ColorDodge("colorDodge", 6, "CD"),
    ColorBurn("colorBurn", 7, "CB"),
    HardLight("hardLight", 8, "HL"),
    SoftLight("softLight", 9, "SL"),
    Difference("difference", 10, "Di"),
    Exclusion("exclusion", 11, "E"),
    Add("add", 12, "A"),
    Subtract("subtract", 13, "Su"),
    Divide("divide", 14, "Dv"),
    LinearBurn("linearBurn", 15, "LB"),
    VividLight("vividLight", 16, "VL"),
    LinearLight("linearLight", 17, "LL"),
    PinLight("pinLight", 18, "PL"),
    HardMix("hardMix", 19, "HM"),
    Hue("hue", 20, "H", separable = false),
    Saturation("saturation", 21, "Sa", separable = false),
    Color("color", 22, "C", separable = false),
    Luminosity("luminosity", 23, "Lu", separable = false),
    DarkerColor("darkerColor", 24, "DC", separable = false),
    LighterColor("lighterColor", 25, "LC", separable = false),
    PassThrough("passThrough", 26, "P"),
    ;

    companion object {
        val LAYER_MODES: List<BlendMode> = entries.filter { it != PassThrough }

        val GROUP_MODES: List<BlendMode> = listOf(PassThrough) + LAYER_MODES

        fun fromId(id: String): BlendMode? = entries.firstOrNull { it.id == id }
    }
}
