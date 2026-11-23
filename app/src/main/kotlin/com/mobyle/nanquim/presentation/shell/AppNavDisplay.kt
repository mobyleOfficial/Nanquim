package com.mobyle.nanquim.presentation.shell

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.runtime.rememberDecoratedNavEntries
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.scene.Scene
import androidx.navigation3.scene.SceneInfo
import androidx.navigation3.scene.SinglePaneSceneStrategy
import androidx.navigation3.scene.rememberSceneState
import androidx.navigation3.ui.NavDisplay
import androidx.navigationevent.compose.rememberNavigationEventState
import com.mobyle.nanquim.core.navigation.AppDestination
import com.mobyle.nanquim.core.navigation.AppNavigator

/**
 * Shows the top of [AppNavigator]'s back stack with Navigation 3. Each destination gets its own saved state and
 * ViewModelStore, so a screen's view model lives exactly as long as the screen is on the stack.
 *
 * The system back button is deliberately not handled here: NanquimApp's own handlers decide what back means (close a
 * panel, cancel the brush edit, close the artwork with its animation, confirm leaving the app) and must keep priority.
 * That is why this uses the NavDisplay overload that takes a scene state and registers no back handler of its own.
 */
@Composable
fun AppNavDisplay(
    navigator: AppNavigator,
    modifier: Modifier = Modifier,
    content: @Composable (AppDestination) -> Unit,
) {
    // The entries are remembered per back stack, so read the content through this to never show a stale one.
    val currentContent by rememberUpdatedState(content)
    val entries =
        rememberDecoratedNavEntries(
            backStack = navigator.backStack,
            entryDecorators =
                listOf(
                    rememberSaveableStateHolderNavEntryDecorator(),
                    rememberViewModelStoreNavEntryDecorator(),
                ),
            entryProvider = { destination -> NavEntry(destination, contentKey = destination.name) { currentContent(it) } },
        )
    val sceneState = rememberSceneState(entries, SinglePaneSceneStrategy(), onBack = navigator::back)
    val navigationEventState = rememberNavigationEventState(currentInfo = SceneInfo(sceneState.currentScene))
    NavDisplay(
        sceneState = sceneState,
        navigationEventState = navigationEventState,
        modifier = modifier,
        transitionSpec = screenTransition,
        popTransitionSpec = screenTransition,
        predictivePopTransitionSpec = { screenTransition() },
    )
}

/** A quick fade between screens; none to or from the canvas, whose own open and close animations take over. */
private val screenTransition: AnimatedContentTransitionScope<Scene<AppDestination>>.() -> ContentTransform = {
    if (initialState.key == CANVAS_KEY || targetState.key == CANVAS_KEY) {
        EnterTransition.None togetherWith ExitTransition.None
    } else {
        fadeIn(tween(SCREEN_FADE_IN_MILLIS, delayMillis = SCREEN_FADE_OUT_MILLIS)) togetherWith fadeOut(tween(SCREEN_FADE_OUT_MILLIS))
    }
}

/** A single-pane scene's key is its entry's content key, the destination's name. */
private val CANVAS_KEY = AppDestination.Canvas.name

private const val SCREEN_FADE_OUT_MILLIS = 90
private const val SCREEN_FADE_IN_MILLIS = 160
