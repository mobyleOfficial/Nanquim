package com.mobyle.nanquim.formats

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class NanquimFileTypeTest {
    @Test
    fun `document MIME type matches the file format`() {
        assertEquals("application/vnd.nanquim.document+zip", NanquimFileType.Document.mimeType)
    }

    @Test
    fun `recognizes extensions case-insensitively`() {
        assertEquals(NanquimFileType.Document, NanquimFileType.fromFileName("Artwork.NANQUIM"))
        assertEquals(NanquimFileType.BrushSet, NanquimFileType.fromFileName("inks.v2.nqbrushset"))
    }

    @Test
    fun `returns null for foreign or missing extensions`() {
        assertNull(NanquimFileType.fromFileName("painting.psd"))
        assertNull(NanquimFileType.fromFileName("nanquim"))
    }
}
