package org.bayton.tools.managedconfig.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection

private val LightColorScheme =
  lightColorScheme(
    primary = BaytonOrange,
    onPrimary = White,
    primaryContainer = White,
    secondaryContainer = BaytonBlue,
    onSecondaryContainer = White,
    onPrimaryContainer = BaytonTextLight,
    errorContainer = BaytonErrorLight,
    error = BaytonError,
    secondary = BaytonGreen,
    tertiary = BaytonBlue,
    surface = BaytonGreySurface,
    background = BaytonGreySurface,
    onSurface = BaytonTextLight,
    onBackground = BaytonInk,
  )

private val DarkColorScheme =
  darkColorScheme(
    primary = BaytonOrange,
    onPrimary = White,
    primaryContainer = BaytonCardDark,
    secondaryContainer = BaytonBlueDark,
    onSecondaryContainer = White,
    onPrimaryContainer = White,
    errorContainer = BaytonErrorDark,
    error = BaytonError,
    secondary = BaytonGreen,
    tertiary = BaytonBlue,
    surface = BaytonSurfaceDark,
    background = BaytonSurfaceDark,
    onSurface = White,
    onBackground = White,
  )

@Composable
fun BaytonManagedConfigTheme(
  darkTheme: Boolean = isSystemInDarkTheme(),
  content: @Composable () -> Unit,
) {
  val colors = if (darkTheme) DarkColorScheme else LightColorScheme

  MaterialTheme(
    colorScheme = colors,
    typography = Typography,
  ) {
    Scaffold(
      modifier = Modifier.fillMaxSize(),
      containerColor = MaterialTheme.colorScheme.surface,
    ) { paddingValues ->
      val layoutDirection = LocalLayoutDirection.current
      val displayCutoutPadding = WindowInsets.displayCutout.asPaddingValues()

      Box(
        modifier =
          Modifier
            .padding(paddingValues)
            .padding(
              start = displayCutoutPadding.calculateStartPadding(layoutDirection),
              end = displayCutoutPadding.calculateEndPadding(layoutDirection),
            ),
      ) {
        content()
      }
    }
  }
}
