package com.mobyle.nanquim.gallery

import android.content.Context
import android.content.res.AssetFileDescriptor
import android.database.Cursor
import android.database.MatrixCursor
import android.graphics.Point
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.provider.DocumentsContract.Root
import android.provider.DocumentsProvider
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import com.mobyle.nanquim.NanquimApplication
import com.mobyle.nanquim.R
import java.io.FileNotFoundException

class GalleryDocumentsProvider : DocumentsProvider() {
    private val application get() = context!!.applicationContext as NanquimApplication

    override fun onCreate(): Boolean = true

    override fun queryRoots(projection: Array<out String>?): Cursor =
        MatrixCursor(projection ?: ROOT_COLUMNS).apply {
            newRow().apply {
                add(Root.COLUMN_ROOT_ID, ROOT_ID)
                add(Root.COLUMN_DOCUMENT_ID, ROOT_DOCUMENT_ID)
                add(Root.COLUMN_TITLE, context!!.getString(R.string.app_name))
                add(Root.COLUMN_FLAGS, Root.FLAG_LOCAL_ONLY or Root.FLAG_SUPPORTS_SEARCH or Root.FLAG_SUPPORTS_IS_CHILD)
                add(Root.COLUMN_ICON, R.mipmap.ic_launcher)
                add(Root.COLUMN_MIME_TYPES, DOCUMENT_MIME_TYPE)
            }
            setNotificationUri(context!!.contentResolver, DocumentsContract.buildRootsUri(authority))
        }

    override fun queryDocument(
        documentId: String,
        projection: Array<out String>?,
    ): Cursor {
        val index = index()
        val cursor = MatrixCursor(projection ?: DOCUMENT_COLUMNS)
        when {
            documentId == ROOT_DOCUMENT_ID ->
                addDirectory(
                    cursor,
                    ROOT_DOCUMENT_ID,
                    context!!.getString(R.string.app_name),
                    index.lastModified,
                )
            documentId.startsWith(STACK_PREFIX) -> {
                val stack = index.stacks[documentId.removePrefix(STACK_PREFIX)] ?: throw FileNotFoundException(documentId)
                addDirectory(cursor, documentId, stack.name, index.stackModified(stack.id))
            }
            documentId.startsWith(ARTWORK_PREFIX) -> {
                val artwork = index.artworks[documentId.removePrefix(ARTWORK_PREFIX)] ?: throw FileNotFoundException(documentId)
                addArtwork(cursor, artwork)
            }
            else -> throw FileNotFoundException(documentId)
        }
        return cursor.watched()
    }

    override fun queryChildDocuments(
        parentDocumentId: String,
        projection: Array<out String>?,
        sortOrder: String?,
    ): Cursor {
        val index = index()
        val cursor = MatrixCursor(projection ?: DOCUMENT_COLUMNS)
        when {
            parentDocumentId == ROOT_DOCUMENT_ID -> {
                for (stack in index.stacks.values) {
                    addDirectory(cursor, STACK_PREFIX + stack.id, stack.name, index.stackModified(stack.id))
                }
                index.artworks.values
                    .filter { it.stackId == null || it.stackId !in index.stacks }
                    .forEach { addArtwork(cursor, it) }
            }
            parentDocumentId.startsWith(STACK_PREFIX) -> {
                val stackId = parentDocumentId.removePrefix(STACK_PREFIX)
                if (stackId !in index.stacks) throw FileNotFoundException(parentDocumentId)
                index.artworks.values
                    .filter { it.stackId == stackId }
                    .forEach { addArtwork(cursor, it) }
            }
            else -> throw FileNotFoundException(parentDocumentId)
        }
        return cursor.watched()
    }

    override fun querySearchDocuments(
        rootId: String,
        query: String,
        projection: Array<out String>?,
    ): Cursor {
        val cursor = MatrixCursor(projection ?: DOCUMENT_COLUMNS)
        val wanted = query.trim()
        index()
            .artworks.values
            .filter {
                wanted.isEmpty() ||
                    it.name.contains(
                        wanted,
                        ignoreCase = true,
                    )
            }.forEach { addArtwork(cursor, it) }
        return cursor.watched()
    }

    override fun isChildDocument(
        parentDocumentId: String,
        documentId: String,
    ): Boolean {
        if (parentDocumentId == documentId) return false
        if (parentDocumentId == ROOT_DOCUMENT_ID) return documentId.startsWith(STACK_PREFIX) || documentId.startsWith(ARTWORK_PREFIX)
        if (!parentDocumentId.startsWith(STACK_PREFIX) || !documentId.startsWith(ARTWORK_PREFIX)) return false
        return index().artworks[documentId.removePrefix(ARTWORK_PREFIX)]?.stackId == parentDocumentId.removePrefix(STACK_PREFIX)
    }

    override fun getDocumentType(documentId: String): String =
        when {
            documentId == ROOT_DOCUMENT_ID || documentId.startsWith(STACK_PREFIX) -> Document.MIME_TYPE_DIR
            documentId.startsWith(ARTWORK_PREFIX) -> DOCUMENT_MIME_TYPE
            else -> throw FileNotFoundException(documentId)
        }

    override fun openDocument(
        documentId: String,
        mode: String,
        signal: CancellationSignal?,
    ): ParcelFileDescriptor {
        if (mode != "r") throw UnsupportedOperationException("read only: $mode")
        val artworkId = artworkIdOf(documentId)
        if (artworkId !in index().artworks) throw FileNotFoundException(documentId)
        val (read, write) = ParcelFileDescriptor.createReliablePipe()
        application.applicationScope.launch {
            try {
                ParcelFileDescriptor.AutoCloseOutputStream(write).use { output ->
                    application.transfer.exportPackage(artworkId, output)
                }
            } catch (failure: Exception) {
                Log.w(TAG, "writing $artworkId failed", failure)
                runCatching { write.closeWithError(failure.message ?: failure.javaClass.simpleName) }
            }
        }
        return read
    }

    override fun openDocumentThumbnail(
        documentId: String,
        sizeHint: Point?,
        signal: CancellationSignal?,
    ): AssetFileDescriptor {
        val file = application.artworkStorage.folderOf(artworkIdOf(documentId)).resolve(THUMBNAIL)
        if (!file.isFile) throw FileNotFoundException(documentId)
        val descriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        return AssetFileDescriptor(descriptor, 0, AssetFileDescriptor.UNKNOWN_LENGTH)
    }

    private fun artworkIdOf(documentId: String): String {
        if (!documentId.startsWith(ARTWORK_PREFIX)) throw FileNotFoundException(documentId)
        val id = documentId.removePrefix(ARTWORK_PREFIX)
        if (id.isEmpty() || id.contains('/') || id.contains('\\') || id.startsWith(".")) throw FileNotFoundException(documentId)
        return id
    }

    private class Index(
        val artworks: Map<String, ArtworkEntity>,
        val stacks: Map<String, StackEntity>,
    ) {
        val lastModified get() = artworks.values.maxOfOrNull { it.modifiedAt }

        fun stackModified(stackId: String) = artworks.values.filter { it.stackId == stackId }.maxOfOrNull { it.modifiedAt }
    }

    private fun index(): Index =
        runBlocking(Dispatchers.IO) {
            val dao = application.galleryDatabase.dao()
            Index(dao.artworks().associateBy { it.id }, dao.stacks().associateBy { it.id })
        }

    private fun addDirectory(
        cursor: MatrixCursor,
        documentId: String,
        name: String,
        modified: Long?,
    ) {
        cursor.newRow().apply {
            add(Document.COLUMN_DOCUMENT_ID, documentId)
            add(Document.COLUMN_DISPLAY_NAME, name)
            add(Document.COLUMN_MIME_TYPE, Document.MIME_TYPE_DIR)
            add(Document.COLUMN_LAST_MODIFIED, modified)
            add(Document.COLUMN_FLAGS, Document.FLAG_DIR_PREFERS_GRID)
            add(Document.COLUMN_SIZE, null)
        }
    }

    private fun addArtwork(
        cursor: MatrixCursor,
        artwork: ArtworkEntity,
    ) {
        val hasThumbnail =
            application.artworkStorage
                .folderOf(artwork.id)
                .resolve(THUMBNAIL)
                .isFile
        cursor.newRow().apply {
            add(Document.COLUMN_DOCUMENT_ID, ARTWORK_PREFIX + artwork.id)
            add(Document.COLUMN_DISPLAY_NAME, displayNameOf(artwork.name))
            add(Document.COLUMN_MIME_TYPE, DOCUMENT_MIME_TYPE)
            add(Document.COLUMN_LAST_MODIFIED, artwork.modifiedAt)
            add(Document.COLUMN_FLAGS, if (hasThumbnail) Document.FLAG_SUPPORTS_THUMBNAIL else 0)
            add(Document.COLUMN_SIZE, null)
        }
    }

    private fun MatrixCursor.watched(): MatrixCursor = apply { setNotificationUri(context!!.contentResolver, changesUri(authority)) }

    private val authority get() = authorityOf(context!!)

    companion object {
        private const val TAG = "GalleryDocuments"
        const val ROOT_ID = "nanquim"
        const val ROOT_DOCUMENT_ID = "gallery"
        const val STACK_PREFIX = "stack:"
        const val ARTWORK_PREFIX = "artwork:"
        const val DOCUMENT_MIME_TYPE = "application/vnd.nanquim.document+zip"
        private const val THUMBNAIL = "thumbnail.png"

        private val ROOT_COLUMNS =
            arrayOf(
                Root.COLUMN_ROOT_ID,
                Root.COLUMN_DOCUMENT_ID,
                Root.COLUMN_TITLE,
                Root.COLUMN_FLAGS,
                Root.COLUMN_ICON,
                Root.COLUMN_MIME_TYPES,
            )
        private val DOCUMENT_COLUMNS =
            arrayOf(
                Document.COLUMN_DOCUMENT_ID,
                Document.COLUMN_DISPLAY_NAME,
                Document.COLUMN_MIME_TYPE,
                Document.COLUMN_LAST_MODIFIED,
                Document.COLUMN_FLAGS,
                Document.COLUMN_SIZE,
            )

        fun authorityOf(context: Context) = "${context.packageName}.documents"

        fun changesUri(authority: String) = DocumentsContract.buildChildDocumentsUri(authority, ROOT_DOCUMENT_ID)

        fun displayNameOf(name: String): String {
            val safe = name.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_").trim().ifEmpty { "artwork" }
            return "$safe.nanquim"
        }

        fun notifyOnChanges(
            context: Context,
            items: StateFlow<List<GalleryItem>>,
            scope: CoroutineScope,
        ) {
            val authority = authorityOf(context)
            scope.launch {
                items.drop(1).collect {
                    context.contentResolver.notifyChange(changesUri(authority), null)
                    context.contentResolver.notifyChange(DocumentsContract.buildRootsUri(authority), null)
                }
            }
        }
    }
}
