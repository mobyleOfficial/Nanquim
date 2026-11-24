package com.mobyle.nanquim.document.command

/**
 * Names a text the app shows, such as a command's title, without depending on a platform's resources. Each platform
 * maps it to its own string resource of the same name (`R.string.<name>` on Android today, `Res.string.<name>` once the
 * strings move to Compose Multiplatform resources).
 */
@JvmInline
value class TextKey(
    val name: String,
)
