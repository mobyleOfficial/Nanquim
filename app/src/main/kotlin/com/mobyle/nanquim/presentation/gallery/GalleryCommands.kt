package com.mobyle.nanquim.presentation.gallery

import com.mobyle.nanquim.domain.command.toCommandArguments
import com.mobyle.nanquim.document.command.CommandResult
import com.mobyle.nanquim.domain.command.CommandDispatcher

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
