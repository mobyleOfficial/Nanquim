package com.mobyle.nanquim.ui.gallery

import androidx.lifecycle.ViewModel
import com.mobyle.nanquim.canvas.MemoryBudgetPolicy
import com.mobyle.nanquim.color.ColorProfileLibrary
import com.mobyle.nanquim.command.CommandDispatcher
import com.mobyle.nanquim.gallery.Gallery
import com.mobyle.nanquim.navigation.AppNavigator
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

@HiltViewModel
class NewCanvasViewModel
    @Inject
    constructor(
        val gallery: Gallery,
        val colorProfiles: ColorProfileLibrary,
        private val memoryBudgetPolicy: MemoryBudgetPolicy,
        private val dispatcher: CommandDispatcher,
        private val navigator: AppNavigator,
    ) : ViewModel() {
        /** The largest canvas the memory budget allows is worked out from this. */
        val memoryBudgetBytes: Long get() = memoryBudgetPolicy.maximumBytes

        fun command(
            commandId: String,
            arguments: Map<String, Any?>,
            onFailure: (String) -> Unit,
        ) = dispatcher.dispatchGalleryCommand(commandId, arguments, onFailure)

        fun back() = navigator.back()
    }
