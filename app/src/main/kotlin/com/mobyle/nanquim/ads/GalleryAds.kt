package com.mobyle.nanquim.ads

import android.app.Activity
import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.google.android.gms.ads.MobileAds
import com.google.android.ump.ConsentInformation
import com.google.android.ump.ConsentRequestParameters
import com.google.android.ump.UserMessagingPlatform
import com.mobyle.nanquim.BuildConfig
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * The gallery's banner: consent first (Google's User Messaging Platform, required in the EEA and the UK), then the
 * Mobile Ads SDK, then [ready] lets the gallery show its banner. Ads never load anywhere else in the app.
 */
class GalleryAds(
    context: Context,
) {
    private val consent: ConsentInformation = UserMessagingPlatform.getConsentInformation(context)
    private val initializing = AtomicBoolean(false)
    private val mainThread = Handler(Looper.getMainLooper())

    /** True when this build has a banner to show at all (see `app/build.gradle.kts`). */
    val enabled: Boolean = BuildConfig.ADS_ENABLED

    var ready: Boolean by mutableStateOf(false)
        private set

    /** Settings › Privacy shows "Ad choices" only where the law asks for a way to change the consent. */
    var privacyOptionsRequired: Boolean by mutableStateOf(false)
        private set

    val bannerUnitId: String = BuildConfig.ADS_GALLERY_BANNER_ID

    /** Asks for consent when needed (a form shows only the first time, and only where required), then starts the SDK. */
    fun start(activity: Activity) {
        if (!enabled) return
        val parameters = ConsentRequestParameters.Builder().build()
        consent.requestConsentInfoUpdate(
            activity,
            parameters,
            {
                UserMessagingPlatform.loadAndShowConsentFormIfRequired(activity) {
                    refreshPrivacyOptions()
                    initializeIfAllowed(activity)
                }
            },
            {
                // Offline or misconfigured: use what was decided last time.
                refreshPrivacyOptions()
                initializeIfAllowed(activity)
            },
        )
        // Consent given in an earlier session lets the banner load without waiting for the update.
        initializeIfAllowed(activity)
    }

    fun showPrivacyOptions(activity: Activity) {
        UserMessagingPlatform.showPrivacyOptionsForm(activity) {
            refreshPrivacyOptions()
            if (!consent.canRequestAds()) ready = false
        }
    }

    private fun refreshPrivacyOptions() {
        privacyOptionsRequired = consent.privacyOptionsRequirementStatus == ConsentInformation.PrivacyOptionsRequirementStatus.REQUIRED
    }

    private fun initializeIfAllowed(activity: Activity) {
        if (!consent.canRequestAds() || !initializing.compareAndSet(false, true)) return
        val context = activity.applicationContext
        // Google recommends starting the SDK off the main thread.
        thread(name = "ads-init") { MobileAds.initialize(context) { mainThread.post { ready = true } } }
    }
}
