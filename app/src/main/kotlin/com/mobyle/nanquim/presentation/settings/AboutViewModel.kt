package com.mobyle.nanquim.presentation.settings

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mobyle.nanquim.core.navigation.AppNavigator
import com.mobyle.nanquim.domain.command.CommandDispatcher
import com.mobyle.nanquim.domain.command.dispatchFeedback
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject

class AboutViewModel(
    private val context: Context,
    private val dispatcher: CommandDispatcher,
    private val navigator: AppNavigator,
) : ViewModel() {
    /** The third-party credits, read once from the bundled CREDITS.md. */
    var credits by mutableStateOf(emptyList<CreditEntry>())
        private set

    init {
        viewModelScope.launch {
            credits =
                withContext(Dispatchers.IO) {
                    runCatching {
                        context.assets
                            .open(
                                CREDITS_ASSET,
                            ).bufferedReader()
                            .readText()
                    }.getOrNull()?.let(::parseCredits).orEmpty()
                }
        }
    }

    fun command(
        commandId: String,
        arguments: JsonObject,
        onOpenFailed: () -> Unit,
    ) = dispatcher.dispatchFeedback(commandId, arguments, onOpenFailed)

    fun back() = navigator.back()

    private companion object {
        const val CREDITS_ASSET = "about/CREDITS.md"
    }
}
