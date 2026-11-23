package com.mobyle.nanquim.di

import javax.inject.Qualifier

/** The coroutine scope that lives as long as the process: on the main thread, and a failed child does not cancel the others. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ApplicationScope
