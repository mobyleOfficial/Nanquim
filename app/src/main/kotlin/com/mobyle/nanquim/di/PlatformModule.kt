package com.mobyle.nanquim.di

import androidx.work.WorkManager
import com.mobyle.nanquim.data.media.WorkManagerTimelapseExporter
import com.mobyle.nanquim.data.platform.AndroidShareSheet
import com.mobyle.nanquim.data.platform.ContentResolverFiles
import com.mobyle.nanquim.domain.platform.ExternalFiles
import com.mobyle.nanquim.domain.platform.ShareSheet
import com.mobyle.nanquim.domain.platform.TimelapseExporter
import org.koin.android.ext.koin.androidContext
import org.koin.dsl.module

/** Android's implementations of what the domain asks of its platform (`domain.platform`); a desktop app swaps this module. */
val platformModule =
    module {
        single<ExternalFiles> { ContentResolverFiles(androidContext().contentResolver) }

        single<ShareSheet> { AndroidShareSheet(androidContext()) }

        single<TimelapseExporter> { WorkManagerTimelapseExporter(WorkManager.getInstance(androidContext())) }
    }
