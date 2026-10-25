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

    fun showCanvas() {
        showGallery()
        backStack.add(AppDestination.Canvas)
    }
}
