package com.mobyle.nanquim.navigation

import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.snapshots.Snapshot

enum class AppDestination {
    Gallery,
    NewCanvas,
    Canvas,
    Settings,
    GestureSettings,
    KeyboardShortcuts,
    About,
    Support,
    Diagnostics,
    LatencyTest,
    ShapeTraces,
    TouchSessions,
    BrushEditor,
}

/**
 * The app's back stack. It lives in the application, not in the UI: commands, the gallery and automation navigate
 * through it with no screen open, and Navigation 3's NavDisplay shows whatever it holds.
 */
class AppNavigator {
    private val destinations = mutableStateListOf(AppDestination.Gallery)

    /** Read by NavDisplay; change it only through the functions below. */
    val backStack: List<AppDestination> get() = destinations

    val current: AppDestination get() = destinations.last()

    val canGoBack: Boolean get() = destinations.size > 1

    fun open(destination: AppDestination) {
        if (current != destination) destinations.add(destination)
    }

    fun back() {
        if (canGoBack) destinations.removeAt(destinations.lastIndex)
    }

    fun showGallery() = replaceWith(listOf(AppDestination.Gallery))

    fun snapshot(): List<String> = destinations.map { it.name }

    /**
     * Brings back the screens of an activity the system recreated after ending the process. The canvas only comes back
     * when its artwork was reopened, and the brush editor never does (its unsaved edit is gone).
     */
    fun restore(
        saved: List<String>,
        artworkOpen: Boolean,
    ) {
        val restored =
            saved
                .mapNotNull { name -> AppDestination.entries.firstOrNull { it.name == name } }
                .filter { it != AppDestination.BrushEditor && (artworkOpen || it != AppDestination.Canvas) }
        if (restored.firstOrNull() != AppDestination.Gallery) return
        replaceWith(restored)
    }

    fun showCanvas() = replaceWith(listOf(AppDestination.Gallery, AppDestination.Canvas))

    /** In one snapshot, so NavDisplay never sees the stack empty or half replaced. */
    private fun replaceWith(stack: List<AppDestination>) {
        Snapshot.withMutableSnapshot {
            destinations.clear()
            destinations.addAll(stack)
        }
    }
}
