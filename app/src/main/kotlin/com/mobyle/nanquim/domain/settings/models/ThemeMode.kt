package com.mobyle.nanquim.domain.settings.models

enum class ThemeMode(
    val id: String,
) {
    System("system"),
    Light("light"),
    Dark("dark"),
    ;

    companion object {
        fun fromId(id: String?): ThemeMode? = entries.firstOrNull { it.id == id }
    }
}
