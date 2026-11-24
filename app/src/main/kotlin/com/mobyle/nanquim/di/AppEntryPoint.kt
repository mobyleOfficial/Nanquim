package com.mobyle.nanquim.di

import com.mobyle.nanquim.core.automation.ControlChannelHost
import com.mobyle.nanquim.data.document.ArtworkStorage
import com.mobyle.nanquim.data.gallery.Gallery
import com.mobyle.nanquim.data.gallery.GalleryDatabase
import com.mobyle.nanquim.data.transfer.ArtworkTransfer
import com.mobyle.nanquim.domain.canvas.CanvasSession
import com.mobyle.nanquim.domain.command.CommandDispatcher
import kotlinx.coroutines.CoroutineScope
import org.koin.core.component.KoinComponent
import org.koin.core.component.get

/**
 * For the places the system creates outside Koin's reach: the documents provider (created before the application), the
 * Glance widget, and the instrumented tests. Composables use `koinInject()` instead.
 */
object AppEntryPoint : KoinComponent {
    fun applicationScope(): CoroutineScope = get(ApplicationScope)

    fun galleryDatabase(): GalleryDatabase = get()

    fun artworkStorage(): ArtworkStorage = get()

    fun transfer(): ArtworkTransfer = get()

    fun gallery(): Gallery = get()

    fun canvasSession(): CanvasSession = get()

    fun commandDispatcher(): CommandDispatcher = get()

    fun controlChannelHost(): ControlChannelHost = get()
}
