package com.omnireader.ui.theme

import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColorScheme = darkColorScheme(
    primary = IndigoDark,
    onPrimary = Color(0xFF00105C),
    primaryContainer = IndigoDarkContainer,
    onPrimaryContainer = IndigoOnDarkContainer,
    secondary = Color(0xFFBBC7FF),
    onSecondary = Color(0xFF08206C),
    secondaryContainer = Color(0xFF3A4DD0),
    onSecondaryContainer = Color(0xFFDDE1FF),
    tertiary = Color(0xFFB9C7FF),
    onTertiary = Color(0xFF082F64),
    background = SurfaceDark,
    onBackground = PaperTextDark,
    surface = SurfaceDark,
    onSurface = PaperTextDark,
    surfaceVariant = ContainerDark,
    onSurfaceVariant = Color(0xFFC3C6D6),
    outline = Color(0xFF41444F),
    outlineVariant = Color(0xFF2A2D38),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6)
)

private val LightColorScheme = lightColorScheme(
    primary = IndigoPrimary,
    onPrimary = Color.White,
    primaryContainer = IndigoContainer,
    onPrimaryContainer = IndigoOnContainer,
    secondary = Color(0xFF4E5FC4),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFDDE1FF),
    onSecondaryContainer = Color(0xFF00105C),
    tertiary = Color(0xFF315998),
    onTertiary = Color.White,
    background = ReaderBgLight,
    onBackground = PaperTextLight,
    surface = SurfaceLight,
    onSurface = PaperTextLight,
    surfaceVariant = ContainerLight,
    onSurfaceVariant = Color(0xFF47464F),
    outline = Color(0xFF78787F),
    outlineVariant = Color(0xFFC9C7CF),
    error = Color(0xFFBA1A1A),
    onError = Color.White,
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002)
)

@Composable
fun OmniReaderTheme(
    darkTheme: Boolean = false,
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}