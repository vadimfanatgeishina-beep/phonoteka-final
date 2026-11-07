package com.phonoteka.ui
import androidx.compose.runtime.staticCompositionLocalOf
import com.phonoteka.security.DemoScope
// Isolated component previews/tests use all scopes; Navigation always provides the actual fixture role.
val LocalDemoPermissions = staticCompositionLocalOf { DemoScope.entries.toSet() }
