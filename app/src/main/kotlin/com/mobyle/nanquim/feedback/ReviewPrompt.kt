package com.mobyle.nanquim.feedback

import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.edit

/** Counts shared or exported artworks and raises [pending] when [ReviewPolicy] says it is a good moment to ask for a review. */
class ReviewPrompt(
    private val preferences: SharedPreferences,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    var pending: Boolean by mutableStateOf(false)
        private set

    fun artworkExported() {
        val exports = preferences.getInt(KEY_EXPORTS, 0) + 1
        preferences.edit { putInt(KEY_EXPORTS, exports) }
        val lastAsked = preferences.getLong(KEY_LAST_ASKED, 0L).takeIf { it > 0L }
        if (ReviewPolicy.shouldAsk(exports, lastAsked, clock())) pending = true
    }

    /** The review flow was shown (or Play declined to show it): start counting again. */
    fun asked() {
        pending = false
        preferences.edit {
            putInt(KEY_EXPORTS, 0)
            putLong(KEY_LAST_ASKED, clock())
        }
    }

    companion object {
        const val PREFERENCES_NAME = "review"
        private const val KEY_EXPORTS = "exportsSinceAsked"
        private const val KEY_LAST_ASKED = "lastAskedMillis"
    }
}
