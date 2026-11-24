package com.mobyle.nanquim.presentation.gallery

import androidx.lifecycle.ViewModel
import com.mobyle.nanquim.core.navigation.AppNavigator
import com.mobyle.nanquim.data.color.ColorProfileLibrary
import com.mobyle.nanquim.data.gallery.Gallery
import com.mobyle.nanquim.domain.canvas.MemoryBudgetPolicy
import com.mobyle.nanquim.domain.command.CommandDispatcher

class NewCanvasViewModel(
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
