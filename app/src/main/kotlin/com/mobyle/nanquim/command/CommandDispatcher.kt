package com.mobyle.nanquim.command

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import com.mobyle.nanquim.document.command.CommandContext
import com.mobyle.nanquim.document.command.CommandRegistry
import com.mobyle.nanquim.document.command.CommandResult

class CommandDispatcher(
    val registry: CommandRegistry,
    val context: CommandContext,
    private val scope: CoroutineScope,
) {
    fun dispatch(
        commandId: String,
        arguments: JsonObject = JsonObject(emptyMap()),
        onResult: (CommandResult) -> Unit = {},
    ) {
        scope.launch {
            val result = registry.execute(context, commandId, arguments)
            if (result is CommandResult.Failure) {
                Log.w(LOG_TAG, "Command $commandId failed (${result.code}): ${result.message}")
            }
            onResult(result)
        }
    }

    private companion object {
        const val LOG_TAG = "NanquimCommands"
    }
}
