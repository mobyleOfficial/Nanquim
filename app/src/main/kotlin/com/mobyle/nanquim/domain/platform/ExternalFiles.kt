package com.mobyle.nanquim.domain.platform

import java.io.InputStream
import java.io.OutputStream

/**
 * Files outside the app that the person picked or another app shared, named by a URI string so the domain needs no
 * platform URI type: a content:// URI on Android, a file path or file:// URI on a desktop.
 */
interface ExternalFiles {
    /** Whether [uri] names a file this platform lets the app open; anything else is refused before it is touched. */
    fun accepts(uri: String): Boolean

    /** Null when the file cannot be opened. */
    fun openInput(uri: String): InputStream?

    /** Replaces the file's contents; null when it cannot be opened for writing. */
    fun openOutput(uri: String): OutputStream?

    /** The name the person sees for the file, when the platform knows one. */
    fun displayName(uri: String): String?
}
