package com.mobyle.nanquim.navigation

import androidx.compose.runtime.mutableStateListOf

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

class AppNavigator {
    private val backStack = mutableStateListOf(AppDestination.Gallery)

    val current: AppDestination get() = backStack.last()

    val canGoBack: Boolean get() = backStack.size > 1

    fun open(destination: AppDestination) {
        if (current != destination) backStack.add(destination)
    }

    fun back() {
        if (canGoBack) backStack.removeAt(backStack.lastIndex)
    }

    fun showGallery() {
        backStack.clear()
        backStack.add(AppDestination.Gallery)
    }

    fun snapshot(): List<String> = backStack.map { it.name }

    /**
     * Brings back the screens of an activity the system recreated after ending the process. The canvas only comes back
     * when its artwork was reopened, and the brush editor never does (its unsaved edit is gone).
     */
    fun restore(
        saved: List<String>,
        artworkOpen: Boolean,
    ) {
        val destinations =
            saved
                .mapNotNull { name -> AppDestination.entries.firstOrNull { it.name == name } }
                .filter { it != AppDestination.BrushEditor && (artworkOpen || it != AppDestination.Canvas) }
        if (destinations.firstOrNull() != AppDestination.Gallery) return
        backStack.clear()
        backStack.addAll(destinations)
    }

    fun showCanvas() {
        showGallery()
        backStack.add(AppDestination.Canvas)
    }
}
