package com.mobyle.nanquim.ui.gallery

import androidx.lifecycle.ViewModel
import com.mobyle.nanquim.ads.GalleryAds
import com.mobyle.nanquim.command.AppCommandIds
import com.mobyle.nanquim.command.CommandDispatcher
import com.mobyle.nanquim.gallery.Gallery
import com.mobyle.nanquim.help.Help
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

/**
 * The gallery's items, selection and open stack stay in [Gallery], where the commands (and so the keyboard, macros and
 * automation) change them too.
 */
@HiltViewModel
class GalleryViewModel
    @Inject
    constructor(
        val gallery: Gallery,
        private val galleryAds: GalleryAds,
        private val help: Help,
        private val dispatcher: CommandDispatcher,
    ) : ViewModel() {
        /** The banner's ad unit, once an ad may show: never over the introduction. */
        val bannerUnitId: String?
            get() = galleryAds.takeIf { it.enabled && it.ready && !help.welcomeVisible }?.bannerUnitId

        fun command(
            commandId: String,
            arguments: Map<String, Any?>,
            onFailure: (String) -> Unit,
        ) = dispatcher.dispatchGalleryCommand(commandId, arguments, onFailure)

        fun openSettings() = dispatcher.dispatch(AppCommandIds.SETTINGS_OPEN)
    }
