package com.mobyle.nanquim.ui.gallery

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.AdSize
import com.google.android.gms.ads.AdView

/**
 * An anchored adaptive banner for the bottom of the gallery. It reserves its height up front so the grid does not jump
 * when the ad arrives, pauses with the activity, and is destroyed as soon as the gallery leaves the screen (opening an
 * artwork), so no ad work competes with drawing.
 */
@Composable
fun GalleryBanner(
    unitId: String,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val context = LocalContext.current
        val widthDp = maxWidth.value.toInt().coerceAtLeast(MINIMUM_WIDTH_DP)
        val size = remember(widthDp) { AdSize.getCurrentOrientationAnchoredAdaptiveBannerAdSize(context, widthDp) }
        val adView =
            remember(widthDp, unitId) {
                AdView(context).apply {
                    adUnitId = unitId
                    setAdSize(size)
                    loadAd(AdRequest.Builder().build())
                }
            }
        val lifecycle = LocalLifecycleOwner.current.lifecycle
        DisposableEffect(adView, lifecycle) {
            val observer =
                LifecycleEventObserver { _, event ->
                    when (event) {
                        Lifecycle.Event.ON_PAUSE -> adView.pause()
                        Lifecycle.Event.ON_RESUME -> adView.resume()
                        else -> Unit
                    }
                }
            lifecycle.addObserver(observer)
            onDispose {
                lifecycle.removeObserver(observer)
                adView.destroy()
            }
        }
        Box(Modifier.fillMaxWidth().height(size.height.dp), contentAlignment = Alignment.Center) {
            AndroidView(factory = { adView })
        }
    }
}

private const val MINIMUM_WIDTH_DP = 320
