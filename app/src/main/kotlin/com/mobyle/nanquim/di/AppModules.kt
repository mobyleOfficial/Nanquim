package com.mobyle.nanquim.di

/** Every module the application starts Koin with. */
val appModules
    get() = listOf(platformModule, appModule, settingsModule, canvasModule, galleryModule, commandModule, viewModelModule)
