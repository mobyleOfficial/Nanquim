package com.mobyle.nanquim.di

import android.content.Context
import com.mobyle.nanquim.core.automation.ControlChannelHost
import com.mobyle.nanquim.data.document.ArtworkStorage
import com.mobyle.nanquim.data.gallery.Gallery
import com.mobyle.nanquim.data.gallery.GalleryDatabase
import com.mobyle.nanquim.data.transfer.ArtworkTransfer
import com.mobyle.nanquim.domain.canvas.CanvasSession
import com.mobyle.nanquim.domain.command.CommandDispatcher
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope

/**
 * For the places Hilt cannot inject: the documents provider (created before the application), the Glance widget,
 * composables deep inside a screen, and the instrumented tests.
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface AppEntryPoint {
    @ApplicationScope
    fun applicationScope(): CoroutineScope

    fun galleryDatabase(): GalleryDatabase

    fun artworkStorage(): ArtworkStorage

    fun transfer(): ArtworkTransfer

    fun gallery(): Gallery

    fun canvasSession(): CanvasSession

    fun commandDispatcher(): CommandDispatcher

    fun controlChannelHost(): ControlChannelHost

    companion object {
        fun from(context: Context): AppEntryPoint = EntryPointAccessors.fromApplication(context.applicationContext, AppEntryPoint::class.java)
    }
}
