package com.mobyle.nanquim.ui.settings

import android.app.Activity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mobyle.nanquim.BuildConfig
import com.mobyle.nanquim.ads.GalleryAds
import com.mobyle.nanquim.command.AppCommandIds
import com.mobyle.nanquim.command.CommandDispatcher
import com.mobyle.nanquim.feedback.dispatchFeedback
import com.mobyle.nanquim.navigation.AppNavigator
import com.mobyle.nanquim.settings.AppSettings
import com.mobyle.nanquim.settings.InputSettings
import com.mobyle.nanquim.settings.StorageCleaner
import com.mobyle.nanquim.settings.StorageUsage
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import javax.inject.Inject

/** The settings themselves stay in [InputSettings] and [AppSettings]; this holds what only the screen needs. */
@HiltViewModel
class SettingsViewModel
    @Inject
    constructor(
        val inputSettings: InputSettings,
        val appSettings: AppSettings,
        private val storage: StorageCleaner,
        private val galleryAds: GalleryAds,
        private val dispatcher: CommandDispatcher,
        private val navigator: AppNavigator,
    ) : ViewModel() {
        var storageUsage by mutableStateOf<StorageUsage?>(null)
            private set

        val adsAvailable: Boolean get() = BuildConfig.ADS_ENABLED

        /** Whether the consent rules where the person lives require a way back to the ad choices form. */
        val adChoicesAvailable: Boolean get() = galleryAds.privacyOptionsRequired

        init {
            refreshStorageUsage()
        }

        fun command(
            commandId: String,
            arguments: JsonObject,
            onOpenFailed: () -> Unit,
        ) = dispatcher.dispatchFeedback(commandId, arguments, onOpenFailed)

        fun clearCache() {
            dispatcher.dispatch(AppCommandIds.SETTINGS_CLEAR_CACHE) { refreshStorageUsage() }
        }

        fun showAdChoices(activity: Activity) = galleryAds.showPrivacyOptions(activity)

        fun openDiagnostics() = dispatcher.dispatch(AppCommandIds.DIAGNOSTICS_OPEN)

        fun back() = navigator.back()

        private fun refreshStorageUsage() {
            viewModelScope.launch { storageUsage = withContext(Dispatchers.IO) { storage.usage() } }
        }
    }
