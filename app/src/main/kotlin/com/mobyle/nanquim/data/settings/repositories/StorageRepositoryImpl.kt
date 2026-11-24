package com.mobyle.nanquim.data.settings.repositories

import com.mobyle.nanquim.domain.settings.models.StorageUsage
import com.mobyle.nanquim.domain.settings.repositories.StorageRepository
import java.io.File

class StorageRepositoryImpl(
    private val artworks: File,
    private val cache: File,
) : StorageRepository {
    override fun usage(): StorageUsage =
        StorageUsage(
            artworkBytes = sizeOf(artworks),
            artworkCount = artworks.listFiles { file -> file.isDirectory }?.size ?: 0,
            cacheBytes = sizeOf(cache),
        )

    override fun clearCaches() {
        CLEARABLE.forEach { File(cache, it).deleteRecursively() }
    }

    private fun sizeOf(folder: File): Long = folder.walkTopDown().filter { it.isFile }.sumOf { it.length() }

    private companion object {
        val CLEARABLE = listOf("brush-thumbnails", "exports")
    }
}
