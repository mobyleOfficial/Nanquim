package com.mobyle.nanquim.di

import android.content.Context
import android.content.Context.MODE_PRIVATE
import com.mobyle.nanquim.BuildConfig
import com.mobyle.nanquim.R
import com.mobyle.nanquim.canvas.CanvasSession
import com.mobyle.nanquim.canvas.MemoryBudgetPolicy
import com.mobyle.nanquim.canvas.ResourceLayerNames
import com.mobyle.nanquim.document.ArtworkStorage
import com.mobyle.nanquim.engine.Engine
import com.mobyle.nanquim.gallery.ArtworkTransfer
import com.mobyle.nanquim.gallery.Gallery
import com.mobyle.nanquim.gallery.GalleryDatabase
import com.mobyle.nanquim.gallery.GalleryDocumentsProvider
import com.mobyle.nanquim.gallery.GalleryNames
import com.mobyle.nanquim.gallery.SampleArtworks
import com.mobyle.nanquim.navigation.AppNavigator
import com.mobyle.nanquim.settings.AppSettings
import com.mobyle.nanquim.settings.StorageCleaner
import com.mobyle.nanquim.telemetry.Telemetry
import com.mobyle.nanquim.widget.WidgetUpdates
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import java.io.File
import javax.inject.Singleton

/** The artworks on disk, their index, and moving them in and out of the app. */
@Module
@InstallIn(SingletonComponent::class)
object GalleryModule {
    @Provides
    @Singleton
    fun artworkStorage(
        @ApplicationContext context: Context,
    ): ArtworkStorage = ArtworkStorage(File(context.filesDir, "artworks").apply { mkdirs() })

    @Provides
    @Singleton
    fun galleryDatabase(
        @ApplicationContext context: Context,
    ): GalleryDatabase = GalleryDatabase.open(context)

    @Provides
    @Singleton
    fun gallery(
        @ApplicationContext context: Context,
        storage: ArtworkStorage,
        database: GalleryDatabase,
        canvasSession: CanvasSession,
        navigator: AppNavigator,
        appSettings: AppSettings,
        telemetry: Telemetry,
        @ApplicationScope scope: CoroutineScope,
    ): Gallery =
        Gallery(
            storage = storage,
            dao = database.dao(),
            canvasSession = canvasSession,
            navigator = navigator,
            layerNames = ResourceLayerNames(context),
            preferences = context.getSharedPreferences(Gallery.PREFERENCES_NAME, MODE_PRIVATE),
            names =
                object : GalleryNames {
                    override fun untitled() = context.getString(R.string.gallery_untitled)

                    override fun copyOf(name: String) = context.getString(R.string.gallery_copy_name, name)

                    override fun stack() = context.getString(R.string.gallery_stack_name)
                },
            scope = scope,
        ).also { gallery ->
            gallery.recordTimelapse = { appSettings.recordTimelapse }
            gallery.telemetry = telemetry
            GalleryDocumentsProvider.notifyOnChanges(context, gallery.items, scope)
            WidgetUpdates.observe(context, gallery.items, scope)
        }

    @Provides
    @Singleton
    fun transfer(
        @ApplicationContext context: Context,
        engine: Engine,
        storage: ArtworkStorage,
        gallery: Gallery,
        canvasSession: CanvasSession,
        memoryBudgetPolicy: MemoryBudgetPolicy,
    ): ArtworkTransfer =
        ArtworkTransfer(
            contentResolver = context.contentResolver,
            engine = engine,
            storage = storage,
            gallery = gallery,
            canvasSession = canvasSession,
            cacheDirectory = context.cacheDir,
            layerNames = ResourceLayerNames(context),
            untitledName = { context.getString(R.string.gallery_untitled) },
            backgroundLayerName = { context.getString(R.string.export_background_layer) },
            appVersion = BuildConfig.VERSION_NAME,
        ) { memoryBudgetPolicy.maximumBytes }

    @Provides
    @Singleton
    fun sampleArtworks(
        @ApplicationContext context: Context,
        transfer: ArtworkTransfer,
        gallery: Gallery,
    ): SampleArtworks =
        SampleArtworks(
            context.assets,
            transfer,
            gallery,
            context.getSharedPreferences(SampleArtworks.PREFERENCES_NAME, MODE_PRIVATE),
            context.cacheDir,
        ) { key ->
            when (key) {
                "night-hills" -> context.getString(R.string.sample_night_hills)
                "ink-flower" -> context.getString(R.string.sample_ink_flower)
                "bouncing-ball" -> context.getString(R.string.sample_bouncing_ball)
                else -> null
            }
        }

    @Provides
    @Singleton
    fun storageCleaner(
        @ApplicationContext context: Context,
        storage: ArtworkStorage,
    ): StorageCleaner = StorageCleaner(storage.root, context.cacheDir)
}
