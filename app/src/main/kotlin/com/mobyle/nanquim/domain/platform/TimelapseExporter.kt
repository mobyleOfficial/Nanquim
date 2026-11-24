package com.mobyle.nanquim.domain.platform

import java.io.File

/** Encodes a recorded timelapse into one video, in work that may outlive the screen that asked for it. */
fun interface TimelapseExporter {
    /** Returns whether [output] was written. [lengthId] is a `TimelapseLength` id. */
    suspend fun export(
        segments: List<File>,
        output: File,
        lengthId: String,
        cardLines: List<String>,
    ): Boolean
}
