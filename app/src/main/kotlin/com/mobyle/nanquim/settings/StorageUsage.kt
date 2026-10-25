package com.mobyle.nanquim.settings

import java.io.File

data class StorageUsage(
    val artworkBytes: Long,
    val artworkCount: Int,
    val cacheBytes: Long,
)

class StorageCleaner(
    private val artworks: File,
    private val cache: File,
) {
    fun usage(): StorageUsage =
        StorageUsage(
            artworkBytes = sizeOf(artworks),
            artworkCount = artworks.listFiles { file -> file.isDirectory }?.size ?: 0,
            cacheBytes = sizeOf(cache),
        )

    fun clearCaches() {
        CLEARABLE.forEach { File(cache, it).deleteRecursively() }
    }

    private fun sizeOf(folder: File): Long = folder.walkTopDown().filter { it.isFile }.sumOf { it.length() }

    private companion object {
        val CLEARABLE = listOf("brush-thumbnails", "exports")
    }
}
