package com.mobyle.nanquim

import android.app.Application
import com.mobyle.nanquim.core.telemetry.Telemetry
import com.mobyle.nanquim.data.engine.EngineHost
import com.mobyle.nanquim.data.platform.AndroidLogSink
import com.mobyle.nanquim.di.appModules
import com.mobyle.nanquim.domain.platform.Logger
import org.koin.android.ext.android.get
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.startKoin

/** The application-wide objects live in the Koin modules under `di`; this class only starts what must run at launch. */
class NanquimApplication : Application() {
    private lateinit var engineHost: EngineHost

    override fun onCreate() {
        super.onCreate()
        Logger.sink = AndroidLogSink
        startKoin {
            androidContext(this@NanquimApplication)
            modules(appModules)
        }
        // Created here, before anything else: crashes and the launch are reported from the start.
        get<Telemetry>()
        engineHost = get()
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        engineHost.onTrimMemory(level)
    }
}
