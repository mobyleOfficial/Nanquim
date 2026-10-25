package com.mobyle.nanquim.ui.diagnostics

import android.annotation.SuppressLint
import android.content.Context
import android.view.MotionEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.mobyle.nanquim.R
import com.mobyle.nanquim.ui.canvas.EngineSurfaceView
import com.mobyle.nanquim.engine.Engine

@Composable
fun LatencyTestScreen(
    engine: Engine,
    onBack: () -> Unit,
) {
    DisposableEffect(engine) {
        engine.setClearColor(red = 1f, green = 1f, blue = 1f)
        onDispose { engine.hideLatencyTestPoint() }
    }
    Box(Modifier.fillMaxSize()) {
        AndroidView(factory = { context -> LatencyTestSurfaceView(context, engine) }, modifier = Modifier.fillMaxSize())
        Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
            TextButton(onClick = onBack, modifier = Modifier.align(Alignment.TopStart)) { Text(stringResource(R.string.back)) }
            Text(
                text = stringResource(R.string.diagnostics_latency_test_hint),
                color = MaterialTheme.colorScheme.onSurface,
                modifier =
                    Modifier
                        .align(Alignment.BottomCenter)
                        .padding(16.dp)
                        .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.8f))
                        .padding(8.dp),
            )
        }
    }
}

@SuppressLint("ViewConstructor")
private class LatencyTestSurfaceView(
    context: Context,
    engine: Engine,
) : EngineSurfaceView(context, engine) {
    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                requestUnbufferedDispatch(event)
                engine.setLatencyTestPoint(event.x, event.y)
            }
            MotionEvent.ACTION_MOVE -> engine.setLatencyTestPoint(event.x, event.y)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> engine.hideLatencyTestPoint()
        }
        return true
    }
}
