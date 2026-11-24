package com.mobyle.nanquim.domain.command

import com.mobyle.nanquim.document.command.CommandErrorCode
import com.mobyle.nanquim.document.command.CommandResult
import kotlinx.serialization.json.JsonObject

/**
 * Dispatches a command that hands off to another app (mail, browser, share sheet); [onOpenFailed] runs when no app
 * could take it, so the screen can say so.
 */
fun CommandDispatcher.dispatchFeedback(
    commandId: String,
    arguments: JsonObject,
    onOpenFailed: () -> Unit,
) {
    dispatch(commandId, arguments) { result ->
        if (result is CommandResult.Failure && result.code == CommandErrorCode.HandlerFailed) onOpenFailed()
    }
}
