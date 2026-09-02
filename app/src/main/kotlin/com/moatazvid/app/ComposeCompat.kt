package com.moatazvid.app

import androidx.compose.runtime.Composable

/** Keeps production root independent from wildcard foundation imports. */
@Composable
fun isSystemInDarkTheme(): Boolean = androidx.compose.foundation.isSystemInDarkTheme()
