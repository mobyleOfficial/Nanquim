package com.mobyle.nanquim.di

import android.content.Context
import org.junit.jupiter.api.Test
import org.koin.core.annotation.KoinExperimentalAPI
import org.koin.core.annotation.KoinInternalApi
import org.koin.test.verify.verify

/**
 * Fails when a ViewModel asks for a type no module provides. Only the ViewModels are checked against their
 * constructors: the other definitions are built by hand in lambdas, which Koin cannot inspect.
 */
@OptIn(KoinExperimentalAPI::class, KoinInternalApi::class)
class KoinModulesTest {
    @Test
    fun `every ViewModel dependency has a definition`() {
        val provided =
            (appModules - viewModelModule)
                .flatMap { module -> module.mappings.values }
                .flatMap { factory -> factory.beanDefinition.secondaryTypes + factory.beanDefinition.primaryType }
        // Context comes from androidContext().
        viewModelModule.verify(extraTypes = provided + Context::class)
    }
}
