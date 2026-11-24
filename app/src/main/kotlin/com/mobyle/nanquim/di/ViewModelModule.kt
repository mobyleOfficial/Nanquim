package com.mobyle.nanquim.di

import com.mobyle.nanquim.presentation.brush.BrushEditorViewModel
import com.mobyle.nanquim.presentation.canvas.CanvasViewModel
import com.mobyle.nanquim.presentation.diagnostics.DiagnosticsViewModel
import com.mobyle.nanquim.presentation.diagnostics.LatencyTestViewModel
import com.mobyle.nanquim.presentation.diagnostics.ShapeTracesViewModel
import com.mobyle.nanquim.presentation.diagnostics.TouchSessionsViewModel
import com.mobyle.nanquim.presentation.gallery.GalleryViewModel
import com.mobyle.nanquim.presentation.gallery.NewCanvasViewModel
import com.mobyle.nanquim.presentation.settings.AboutViewModel
import com.mobyle.nanquim.presentation.settings.GestureSettingsViewModel
import com.mobyle.nanquim.presentation.settings.KeyboardShortcutsViewModel
import com.mobyle.nanquim.presentation.settings.SettingsViewModel
import com.mobyle.nanquim.presentation.settings.SupportViewModel
import com.mobyle.nanquim.presentation.shell.AppShellViewModel
import org.koin.core.module.dsl.viewModelOf
import org.koin.dsl.module

/** One ViewModel per screen, scoped by Navigation 3 to its entry on the back stack. */
val viewModelModule =
    module {
        viewModelOf(::AppShellViewModel)
        viewModelOf(::GalleryViewModel)
        viewModelOf(::NewCanvasViewModel)
        viewModelOf(::CanvasViewModel)
        viewModelOf(::BrushEditorViewModel)
        viewModelOf(::SettingsViewModel)
        viewModelOf(::GestureSettingsViewModel)
        viewModelOf(::KeyboardShortcutsViewModel)
        viewModelOf(::AboutViewModel)
        viewModelOf(::SupportViewModel)
        viewModelOf(::DiagnosticsViewModel)
        viewModelOf(::LatencyTestViewModel)
        viewModelOf(::ShapeTracesViewModel)
        viewModelOf(::TouchSessionsViewModel)
    }
