package com.mobyle.nanquim.di

import android.content.Context
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
import com.mobyle.nanquim.engine.Engine
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/** The settings repositories, each backed by its own SharedPreferences file. */
@Module
@InstallIn(SingletonComponent::class)
object SettingsModule {
    private const val QUICK_MENU_PREFERENCES = "quick-menu"

    @Provides
    @Singleton
    fun appSettings(
        @ApplicationContext context: Context,
    ): AppSettingsRepository =
        AppSettingsRepositoryImpl(context.getSharedPreferences(AppSettingsRepositoryImpl.PREFERENCES_NAME, MODE_PRIVATE))

    @Provides
    @Singleton
    fun inputSettings(
        @ApplicationContext context: Context,
        engine: Engine,
    ): InputSettingsRepository =
        InputSettingsRepositoryImpl(context.getSharedPreferences(InputSettingsRepositoryImpl.PREFERENCES_NAME, MODE_PRIVATE), engine)

    @Provides
    @Singleton
    fun quickMenu(
        @ApplicationContext context: Context,
    ): QuickMenuRepository =
        QuickMenuRepositoryImpl(
            context.getSharedPreferences(QUICK_MENU_PREFERENCES, MODE_PRIVATE),
            context.getString(R.string.quick_menu_default_preset),
        )

    @Provides
    @Singleton
    fun keyboardShortcuts(
        @ApplicationContext context: Context,
    ): KeyboardShortcutsRepository =
        KeyboardShortcutsRepositoryImpl(context.getSharedPreferences(KeyboardShortcutsRepositoryImpl.PREFERENCES_NAME, MODE_PRIVATE))

    @Provides
    @Singleton
    fun storage(
        @ApplicationContext context: Context,
        artworks: ArtworkStorage,
    ): StorageRepository = StorageRepositoryImpl(artworks.root, context.cacheDir)
}
