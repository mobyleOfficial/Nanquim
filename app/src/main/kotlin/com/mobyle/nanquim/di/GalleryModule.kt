package com.mobyle.nanquim.di

import android.content.Context.MODE_PRIVATE
import com.mobyle.nanquim.BuildConfig
import com.mobyle.nanquim.R
import com.mobyle.nanquim.core.telemetry.Telemetry
import com.mobyle.nanquim.data.canvas.ResourceLayerNames
import com.mobyle.nanquim.data.document.ArtworkStorage
import com.mobyle.nanquim.data.gallery.Gallery
import com.mobyle.nanquim.data.gallery.GalleryDatabase
import com.mobyle.nanquim.data.gallery.GalleryDocumentsProvider
import com.mobyle.nanquim.data.gallery.GalleryNames
import com.mobyle.nanquim.data.gallery.SampleArtworks
import com.mobyle.nanquim.data.transfer.ArtworkTransfer
import com.mobyle.nanquim.domain.canvas.MemoryBudgetPolicy
import com.mobyle.nanquim.domain.settings.repositories.AppSettingsRepository
import com.mobyle.nanquim.presentation.widget.WidgetUpdates
import kotlinx.coroutines.CoroutineScope
import org.koin.android.ext.koin.androidContext
import org.koin.dsl.module
import java.io.File

/** The artworks on disk, their index, and moving them in and out of the app. */
val galleryModule =
    module {
        single { ArtworkStorage(File(androidContext().filesDir, "artworks").apply { mkdirs() }) }

        single { GalleryDatabase.open(androidContext()) }

        single {
            val context = androidContext()
            val scope = get<CoroutineScope>(ApplicationScope)
            val appSettings = get<AppSettingsRepository>()
            Gallery(
                storage = get(),
                dao = get<GalleryDatabase>().dao(),
                canvasSession = get(),
                navigator = get(),
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
                gallery.telemetry = get<Telemetry>()
                GalleryDocumentsProvider.notifyOnChanges(context, gallery.items, scope)
                WidgetUpdates.observe(context, gallery.items, scope)
            }
        }

        single {
            val context = androidContext()
            val memoryBudgetPolicy = get<MemoryBudgetPolicy>()
            ArtworkTransfer(
                contentResolver = context.contentResolver,
                engine = get(),
                storage = get(),
                gallery = get(),
                canvasSession = get(),
                cacheDirectory = context.cacheDir,
                layerNames = ResourceLayerNames(context),
                untitledName = { context.getString(R.string.gallery_untitled) },
                backgroundLayerName = { context.getString(R.string.export_background_layer) },
                appVersion = BuildConfig.VERSION_NAME,
            ) { memoryBudgetPolicy.maximumBytes }
        }

        single {
            val context = androidContext()
            SampleArtworks(
                context.assets,
                get(),
                get(),
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
        }
    }
