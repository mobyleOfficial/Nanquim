package com.mobyle.nanquim.di

import org.koin.core.qualifier.named

/** The coroutine scope that lives as long as the process: on the main thread, and a failed child does not cancel the others. */
val ApplicationScope = named("ApplicationScope")
