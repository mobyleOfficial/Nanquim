package com.mobyle.nanquim

import android.app.Application
import com.mobyle.nanquim.canvas.EngineHost
import com.mobyle.nanquim.telemetry.Telemetry
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

/** The application-wide objects live in the Hilt modules under `di`; this class only starts what must run at launch. */
@HiltAndroidApp
class NanquimApplication : Application() {
    /** Injected (and so created) in onCreate, before anything else: crashes and the launch are reported from the start. */
    @Inject
    lateinit var telemetry: Telemetry

    @Inject
    lateinit var engineHost: EngineHost

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        engineHost.onTrimMemory(level)
    }
}
