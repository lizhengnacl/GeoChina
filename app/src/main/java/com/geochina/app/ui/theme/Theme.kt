package com.geochina.app.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import com.geochina.app.ui.ThemeMode

private val AMapBlue = Color(0xFF1A66FF)
private val AMapOrange = Color(0xFFF37327)

private val LightScheme = lightColorScheme(
    primary = AMapBlue,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDCE8FF),
    onPrimaryContainer = Color(0xFF001A44),
    secondary = Color(0xFF006D9C),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFD5EFFF),
    onSecondaryContainer = Color(0xFF001D31),
    tertiary = AMapOrange,
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFFE2D0),
    onTertiaryContainer = Color(0xFF371000),
    background = Color(0xFFF7FAFF),
    onBackground = Color(0xFF1D2129),
    surface = Color.White,
    onSurface = Color(0xFF1D2129),
    surfaceVariant = Color(0xFFE6EAF0),
    onSurfaceVariant = Color(0xFF546170),
    outline = Color(0xFF7A8699),
)

private val DarkScheme = darkColorScheme(
    primary = Color(0xFF8EB2FF),
    onPrimary = Color(0xFF003A91),
    primaryContainer = Color(0xFF004DC9),
    onPrimaryContainer = Color(0xFFDCE8FF),
    secondary = Color(0xFF8BD3FF),
    onSecondary = Color(0xFF00344E),
    secondaryContainer = Color(0xFF00506F),
    onSecondaryContainer = Color(0xFFD5EFFF),
    tertiary = Color(0xFFFFB088),
    onTertiary = Color(0xFF542100),
    tertiaryContainer = Color(0xFF7A3100),
    onTertiaryContainer = Color(0xFFFFE2D0),
    background = Color(0xFF101419),
    onBackground = Color(0xFFE6EAF0),
    surface = Color(0xFF171B21),
    onSurface = Color(0xFFE6EAF0),
    surfaceVariant = Color(0xFF2B313D),
    onSurfaceVariant = Color(0xFFC3CAD5),
    outline = Color(0xFF8C96A8),
)

@Composable
fun GeoChinaTheme(
    themeMode: ThemeMode,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val systemDark = isSystemInDarkTheme()
    val darkTheme = when (themeMode) {
        ThemeMode.System -> systemDark
        ThemeMode.Light -> false
        ThemeMode.Dark -> true
    }
    val colorScheme = if (darkTheme) DarkScheme else LightScheme
    val window = (context as? Activity)?.window
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        window?.navigationBarColor = android.graphics.Color.TRANSPARENT
    }
    MaterialTheme(
        colorScheme = colorScheme,
        typography = MaterialTheme.typography,
        content = content,
    )
}
