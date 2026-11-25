package com.mobyle.nanquim.data.ads

import android.app.Activity
import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.google.android.gms.ads.MobileAds
import com.google.android.gms.ads.RequestConfiguration
import com.google.android.ump.ConsentDebugSettings
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
    allowedByUser: Boolean,
) {
    private val consent: ConsentInformation = UserMessagingPlatform.getConsentInformation(context)
    private val initializing = AtomicBoolean(false)
    private val mainThread = Handler(Looper.getMainLooper())

    /**
     * True when this build has a banner to show at all (see `app/build.gradle.kts`) and the user has not turned it off
     * in Settings (read once per launch).
     */
    val enabled: Boolean = BuildConfig.ADS_ENABLED && allowedByUser

    var ready: Boolean by mutableStateOf(false)
        private set

    /** Settings › Privacy shows "Ad choices" only where the law asks for a way to change the consent. */
    var privacyOptionsRequired: Boolean by mutableStateOf(false)
        private set

    val bannerUnitId: String = BuildConfig.ADS_GALLERY_BANNER_ID

    /** Asks for consent when needed (a form shows only the first time, and only where required), then starts the SDK. */
    fun start(activity: Activity) {
        if (!enabled) return
        val parameters = ConsentRequestParameters.Builder().apply { consentDebugSettings(activity)?.let(::setConsentDebugSettings) }.build()
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
        // The gallery is for every age: no ad above PG, whatever the AdMob console says.
        MobileAds.setRequestConfiguration(
            RequestConfiguration.Builder().setMaxAdContentRating(RequestConfiguration.MAX_AD_CONTENT_RATING_PG).build(),
        )
        // Google recommends starting the SDK off the main thread.
        thread(name = "ads-init") { MobileAds.initialize(context) { mainThread.post { ready = true } } }
    }

    /**
     * Debug builds only, from `admob.debugGeography` and `admob.testDeviceIds` in local.properties: pretend the device
     * is in that region and forget the last answer, so the consent form shows on every launch.
     */
    private fun consentDebugSettings(activity: Activity): ConsentDebugSettings? {
        val geography =
            when (BuildConfig.ADS_DEBUG_GEOGRAPHY) {
                "EEA" -> ConsentDebugSettings.DebugGeography.DEBUG_GEOGRAPHY_EEA
                "OTHER" -> ConsentDebugSettings.DebugGeography.DEBUG_GEOGRAPHY_OTHER
                "REGULATED_US_STATE" -> ConsentDebugSettings.DebugGeography.DEBUG_GEOGRAPHY_REGULATED_US_STATE
                else -> return null
            }
        consent.reset()
        return ConsentDebugSettings
            .Builder(activity)
            .setDebugGeography(geography)
            .apply {
                BuildConfig.ADS_TEST_DEVICE_IDS
                    .split(',')
                    .map(String::trim)
                    .filter(String::isNotEmpty)
                    .forEach(::addTestDeviceHashedId)
            }.build()
    }
}
