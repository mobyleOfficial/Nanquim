package com.mobyle.nanquim.formats

enum class NanquimFileType(
    val extension: String,
    val mimeType: String,
) {
    Document("nanquim", "application/vnd.nanquim.document+zip"),
    Brush("nqbrush", "application/vnd.nanquim.brush+zip"),
    BrushSet("nqbrushset", "application/vnd.nanquim.brushset+zip"),
    Palette("nqpalette", "application/vnd.nanquim.palette+json"),
    Gradient("nqgradient", "application/vnd.nanquim.gradient+json"),
    ;

    companion object {
        fun fromFileName(fileName: String): NanquimFileType? {
            val extension = fileName.substringAfterLast('.', missingDelimiterValue = "").lowercase()
            return entries.firstOrNull { it.extension == extension }
        }
    }
}
