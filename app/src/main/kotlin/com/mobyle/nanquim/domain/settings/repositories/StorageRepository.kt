package com.mobyle.nanquim.domain.settings.repositories

import com.mobyle.nanquim.domain.settings.models.StorageUsage

/** The space the app takes on the device. Both calls touch the disk; keep them off the main thread. */
interface StorageRepository {
    fun usage(): StorageUsage

    /** Deletes what the app can make again (brush thumbnails, finished exports); never artworks. */
    fun clearCaches()
}
