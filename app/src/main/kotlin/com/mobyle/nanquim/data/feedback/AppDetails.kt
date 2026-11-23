package com.mobyle.nanquim.data.feedback

import android.content.Context
import android.os.Build
import com.mobyle.nanquim.BuildConfig
import com.mobyle.nanquim.R
import com.mobyle.nanquim.domain.feedback.AppInfo
import com.mobyle.nanquim.domain.settings.repositories.AppSettingsRepository
import com.mobyle.nanquim.presentation.settings.SupportLink
import com.mobyle.nanquim.presentation.settings.parseSupportLinks
import dagger.Lazy
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** Facts about this installation that bug reports and the support screen show. */
@Singleton
class AppDetails
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
        private val appSettings: Lazy<AppSettingsRepository>,
    ) {
        fun supportLinks(): List<SupportLink> = parseSupportLinks(context.resources.getStringArray(R.array.support_links).toList())

        /** Version, device and renderer for bug reports, without the engine: a report must work even when the engine does not start. */
        fun appInfo(): AppInfo {
            val settings = appSettings.get()
            return AppInfo(
                version = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
                device = "${Build.MANUFACTURER} ${Build.MODEL}",
                android = "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
                renderer = (settings.activeRenderer ?: settings.renderer).id,
            )
        }
    }
