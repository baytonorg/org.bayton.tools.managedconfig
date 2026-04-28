package org.bayton.tools.managedconfig.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val DarkColorScheme =
  darkColorScheme(
    primary = BaytonOrange,
    onPrimary = Color.White,
    primaryContainer = Color(0xFF252525),
    onPrimaryContainer = Color.White,
    secondary = BaytonGreen,
    onSecondary = Color.White,
    secondaryContainer = BaytonBlueDark,
    onSecondaryContainer = Color.White,
    tertiary = BaytonBlue,
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFF3A2200),
    onTertiaryContainer = BaytonOrangeLight,
    inversePrimary = BaytonGreen,
    error = BaytonError,
    onError = Color.White,
    errorContainer = BaytonErrorDark,
    onErrorContainer = Color(0xFFFFDAD4),
    background = BaytonNight,
    onBackground = Color.White,
    surface = BaytonSurfaceDark,
    onSurface = Color.White,
    surfaceVariant = BaytonSlate,
    onSurfaceVariant = Color(0xFFD7D2CC),
    outline = Color(0xFF8E8A85),
  )

private val LightColorScheme =
  lightColorScheme(
    primary = BaytonOrange,
    onPrimary = Color.White,
    primaryContainer = Color.White,
    onPrimaryContainer = BaytonTextLight,
    secondary = BaytonGreen,
    onSecondary = Color.White,
    secondaryContainer = BaytonBlue,
    onSecondaryContainer = Color.White,
    tertiary = BaytonBlue,
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFFE2D6),
    onTertiaryContainer = BaytonTextLight,
    inversePrimary = BaytonGreen,
    error = BaytonError,
    onError = Color.White,
    errorContainer = BaytonErrorLight,
    onErrorContainer = BaytonError,
    background = BaytonMist,
    onBackground = BaytonInk,
    surface = BaytonSurfaceLight,
    onSurface = BaytonInk,
    surfaceVariant = Color(0xFFF0E5DE),
    onSurfaceVariant = Color(0xFF574A42),
    outline = Color(0xFF8A776C),
  )

@Composable
fun BaytonManagedConfigTheme(
  darkTheme: Boolean = isSystemInDarkTheme(),
  content: @Composable () -> Unit,
) {
  val colorScheme =
    if (darkTheme) DarkColorScheme else LightColorScheme

  val view = LocalView.current
  val context = LocalContext.current

  SideEffect {
    val window = (context as? android.app.Activity)?.window
    window?.let {
      it.statusBarColor = colorScheme.surface.toArgb()
      it.navigationBarColor = colorScheme.surface.toArgb()
      WindowCompat.getInsetsController(it, view).apply {
        isAppearanceLightStatusBars = !darkTheme
        isAppearanceLightNavigationBars = !darkTheme
      }
    }
  }

  MaterialTheme(colorScheme = colorScheme, typography = Typography, content = content)
}
