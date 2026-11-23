package com.mobyle.nanquim.di

import android.content.Context.MODE_PRIVATE
import com.mobyle.nanquim.R
import com.mobyle.nanquim.data.document.ArtworkStorage
import com.mobyle.nanquim.data.settings.repositories.AppSettingsRepositoryImpl
import com.mobyle.nanquim.data.settings.repositories.InputSettingsRepositoryImpl
import com.mobyle.nanquim.data.settings.repositories.KeyboardShortcutsRepositoryImpl
import com.mobyle.nanquim.data.settings.repositories.QuickMenuRepositoryImpl
import com.mobyle.nanquim.data.settings.repositories.StorageRepositoryImpl
import com.mobyle.nanquim.domain.settings.repositories.AppSettingsRepository
import com.mobyle.nanquim.domain.settings.repositories.InputSettingsRepository
import com.mobyle.nanquim.domain.settings.repositories.KeyboardShortcutsRepository
import com.mobyle.nanquim.domain.settings.repositories.QuickMenuRepository
import com.mobyle.nanquim.domain.settings.repositories.StorageRepository
import org.koin.android.ext.koin.androidContext
import org.koin.dsl.module

private const val QUICK_MENU_PREFERENCES = "quick-menu"

/** The settings repositories, each backed by its own SharedPreferences file. */
val settingsModule =
    module {
        single<AppSettingsRepository> {
            AppSettingsRepositoryImpl(androidContext().getSharedPreferences(AppSettingsRepositoryImpl.PREFERENCES_NAME, MODE_PRIVATE))
        }

        single<InputSettingsRepository> {
            InputSettingsRepositoryImpl(
                androidContext().getSharedPreferences(InputSettingsRepositoryImpl.PREFERENCES_NAME, MODE_PRIVATE),
                get(),
            )
        }

        single<QuickMenuRepository> {
            val context = androidContext()
            QuickMenuRepositoryImpl(
                context.getSharedPreferences(QUICK_MENU_PREFERENCES, MODE_PRIVATE),
                context.getString(R.string.quick_menu_default_preset),
            )
        }

        single<KeyboardShortcutsRepository> {
            KeyboardShortcutsRepositoryImpl(
                androidContext().getSharedPreferences(KeyboardShortcutsRepositoryImpl.PREFERENCES_NAME, MODE_PRIVATE),
            )
        }

        single<StorageRepository> { StorageRepositoryImpl(get<ArtworkStorage>().root, androidContext().cacheDir) }
    }
