package com.mobyle.nanquim.ui.gallery

import com.mobyle.nanquim.command.CommandDispatcher
import com.mobyle.nanquim.command.toCommandArguments
import com.mobyle.nanquim.document.command.CommandResult

/** Dispatches a gallery or transfer command; [onFailure] gets the failure's message for [galleryFailureMessage]. */
fun CommandDispatcher.dispatchGalleryCommand(
    commandId: String,
    arguments: Map<String, Any?>,
    onFailure: (String) -> Unit,
) {
    dispatch(commandId, arguments.toCommandArguments()) { result ->
        if (result is CommandResult.Failure) onFailure(result.message)
    }
}
