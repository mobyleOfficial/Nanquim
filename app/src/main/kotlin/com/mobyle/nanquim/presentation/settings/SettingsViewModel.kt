package com.mobyle.nanquim.presentation.settings

import android.app.Activity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mobyle.nanquim.BuildConfig
import com.mobyle.nanquim.core.navigation.AppNavigator
import com.mobyle.nanquim.data.ads.GalleryAds
import com.mobyle.nanquim.domain.command.AppCommandIds
import com.mobyle.nanquim.domain.command.CommandDispatcher
import com.mobyle.nanquim.domain.command.dispatchFeedback
import com.mobyle.nanquim.domain.settings.models.StorageUsage
import com.mobyle.nanquim.domain.settings.repositories.AppSettingsRepository
import com.mobyle.nanquim.domain.settings.repositories.InputSettingsRepository
import com.mobyle.nanquim.domain.settings.repositories.StorageRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject

/** The settings themselves stay in [InputSettingsRepository] and [AppSettingsRepository]; this holds what only the screen needs. */
class SettingsViewModel(
    val inputSettings: InputSettingsRepository,
    val appSettings: AppSettingsRepository,
    private val storage: StorageRepository,
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
