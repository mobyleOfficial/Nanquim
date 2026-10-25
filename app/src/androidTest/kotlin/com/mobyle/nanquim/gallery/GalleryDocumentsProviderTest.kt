package com.mobyle.nanquim.gallery

import android.graphics.BitmapFactory
import android.graphics.Point
import android.os.Bundle
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.provider.DocumentsContract.Root
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import com.mobyle.nanquim.NanquimApplication
import java.util.zip.ZipInputStream

@RunWith(AndroidJUnit4::class)
class GalleryDocumentsProviderTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val application = context.applicationContext as NanquimApplication
    private val authority = GalleryDocumentsProvider.authorityOf(context)
    private val resolver = context.contentResolver

    private fun artworks(): List<ArtworkEntity> = runBlocking { application.galleryDatabase.dao().artworks() }

    @Test
    fun rootIsTheGallery() {
        resolver.query(DocumentsContract.buildRootsUri(authority), null, null, null, null)!!.use { cursor ->
            assertEquals(1, cursor.count)
            cursor.moveToFirst()
            assertEquals(GalleryDocumentsProvider.ROOT_DOCUMENT_ID, cursor.getString(cursor.getColumnIndexOrThrow(Root.COLUMN_DOCUMENT_ID)))
            assertTrue(cursor.getInt(cursor.getColumnIndexOrThrow(Root.COLUMN_FLAGS)) and Root.FLAG_SUPPORTS_SEARCH != 0)
        }
    }

    @Test
    fun childrenListEveryArtworkOutsideStacksAndEveryStack() {
        val artworks = artworks()
        val stacks = runBlocking { application.galleryDatabase.dao().stacks() }
        val uri = DocumentsContract.buildChildDocumentsUri(authority, GalleryDocumentsProvider.ROOT_DOCUMENT_ID)
        val ids = mutableListOf<String>()
        resolver.query(uri, null, null, null, null)!!.use { cursor ->
            while (cursor.moveToNext()) ids += cursor.getString(cursor.getColumnIndexOrThrow(Document.COLUMN_DOCUMENT_ID))
        }
        val stackIds = stacks.map { it.id }.toSet()
        val expected =
            stacks.map { GalleryDocumentsProvider.STACK_PREFIX + it.id } +
                artworks.filter { it.stackId == null || it.stackId !in stackIds }.map { GalleryDocumentsProvider.ARTWORK_PREFIX + it.id }
        assertEquals(expected.toSet(), ids.toSet())
    }

    @Test
    fun anArtworkReadsAsAPackageWithItsThumbnail() {
        val artwork = artworks().maxByOrNull { it.modifiedAt }
        assumeTrue("no artwork in the gallery", artwork != null)
        val documentId = GalleryDocumentsProvider.ARTWORK_PREFIX + artwork!!.id
        val uri = DocumentsContract.buildDocumentUri(authority, documentId)
        resolver.query(uri, null, null, null, null)!!.use { cursor ->
            cursor.moveToFirst()
            assertEquals(
                GalleryDocumentsProvider.DOCUMENT_MIME_TYPE,
                cursor.getString(cursor.getColumnIndexOrThrow(Document.COLUMN_MIME_TYPE)),
            )
            assertTrue(cursor.getString(cursor.getColumnIndexOrThrow(Document.COLUMN_DISPLAY_NAME)).endsWith(".nanquim"))
            assertTrue(cursor.getInt(cursor.getColumnIndexOrThrow(Document.COLUMN_FLAGS)) and Document.FLAG_SUPPORTS_THUMBNAIL != 0)
        }
        val names = mutableListOf<String>()
        resolver.openInputStream(uri)!!.use { input ->
            ZipInputStream(input).use { zip ->
                while (true) names += (zip.nextEntry ?: break).name
            }
        }
        assertEquals("mimetype", names.first())
        assertTrue("manifest.json" in names)
        val thumbnail = DocumentsContract.getDocumentThumbnail(resolver, uri, Point(256, 256), null)
        assertNotNull(thumbnail)
        assertTrue(thumbnail!!.width > 0)
        resolver.openAssetFileDescriptor(uri, "r")!!.close()
        assertNotNull(
            BitmapFactory.decodeFile(
                application.artworkStorage
                    .folderOf(artwork.id)
                    .resolve("thumbnail.png")
                    .path,
            ),
        )
    }

    @Test
    fun searchFindsArtworksByName() {
        val artwork = artworks().firstOrNull { it.name.length >= 2 }
        assumeTrue("no named artwork", artwork != null)
        val uri = DocumentsContract.buildSearchDocumentsUri(authority, GalleryDocumentsProvider.ROOT_ID, artwork!!.name.take(2))
        val ids = mutableListOf<String>()
        resolver.query(uri, null, Bundle(), null)!!.use { cursor ->
            while (cursor.moveToNext()) ids += cursor.getString(cursor.getColumnIndexOrThrow(Document.COLUMN_DOCUMENT_ID))
        }
        assertTrue(GalleryDocumentsProvider.ARTWORK_PREFIX + artwork.id in ids)
    }
}
