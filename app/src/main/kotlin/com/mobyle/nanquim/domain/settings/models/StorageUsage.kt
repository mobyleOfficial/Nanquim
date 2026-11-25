package com.mobyle.nanquim.domain.settings.models

data class StorageUsage(
    val artworkBytes: Long,
    val artworkCount: Int,
    val cacheBytes: Long,
)
