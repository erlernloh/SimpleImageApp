package com.imagedit.app.ui.accessibility

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import android.content.Context
import android.provider.Settings

/**
 * High contrast mode support for smart photo enhancement features.
 * Provides enhanced color schemes for users with visual impairments.
 */
object HighContrastSupport {
    
    /**
     * Check if high contrast mode is enabled in system settings.
     */
    fun isHighContrastEnabled(context: Context): Boolean {
        return try {
            Settings.Secure.getInt(
                context.contentResolver,
                "high_text_contrast_enabled",
                0
            ) == 1
        } catch (e: Exception) {
            false
        }
    }
    
    /**
     * High contrast color scheme for light mode.
     */
    val highContrastLightColorScheme = lightColorScheme(
        primary = Color(0xFF000000),
        onPrimary = Color(0xFFFFFFFF),
        primaryContainer = Color(0xFF000000),
        onPrimaryContainer = Color(0xFFFFFFFF),
        secondary = Color(0xFF000000),
        onSecondary = Color(0xFFFFFFFF),
        secondaryContainer = Color(0xFFE0E0E0),
        onSecondaryContainer = Color(0xFF000000),
        tertiary = Color(0xFF000000),
        onTertiary = Color(0xFFFFFFFF),
        tertiaryContainer = Color(0xFFE0E0E0),
        onTertiaryContainer = Color(0xFF000000),
        error = Color(0xFF000000),
        onError = Color(0xFFFFFFFF),
        errorContainer = Color(0xFFFFE0E0),
        onErrorContainer = Color(0xFF000000),
        background = Color(0xFFFFFFFF),
        onBackground = Color(0xFF000000),
        surface = Color(0xFFFFFFFF),
        onSurface = Color(0xFF000000),
        surfaceVariant = Color(0xFFF0F0F0),
        onSurfaceVariant = Color(0xFF000000),
        outline = Color(0xFF000000),
        outlineVariant = Color(0xFF808080),
        scrim = Color(0xFF000000),
        inverseSurface = Color(0xFF000000),
        inverseOnSurface = Color(0xFFFFFFFF),
        inversePrimary = Color(0xFFFFFFFF),
        surfaceDim = Color(0xFFE0E0E0),
        surfaceBright = Color(0xFFFFFFFF),
        surfaceContainerLowest = Color(0xFFFFFFFF),
        surfaceContainerLow = Color(0xFFF8F8F8),
        surfaceContainer = Color(0xFFF0F0F0),
        surfaceContainerHigh = Color(0xFFE8E8E8),
        surfaceContainerHighest = Color(0xFFE0E0E0)
    )
    
    /**
     * High contrast color scheme for dark mode.
     */
    val highContrastDarkColorScheme = darkColorScheme(
        primary = Color(0xFFFFFFFF),
        onPrimary = Color(0xFF000000),
        primaryContainer = Color(0xFFFFFFFF),
        onPrimaryContainer = Color(0xFF000000),
        secondary = Color(0xFFFFFFFF),
        onSecondary = Color(0xFF000000),
        secondaryContainer = Color(0xFF404040),
        onSecondaryContainer = Color(0xFFFFFFFF),
        tertiary = Color(0xFFFFFFFF),
        onTertiary = Color(0xFF000000),
        tertiaryContainer = Color(0xFF404040),
        onTertiaryContainer = Color(0xFFFFFFFF),
        error = Color(0xFFFFFFFF),
        onError = Color(0xFF000000),
        errorContainer = Color(0xFF800000),
        onErrorContainer = Color(0xFFFFFFFF),
        background = Color(0xFF000000),
        onBackground = Color(0xFFFFFFFF),
        surface = Color(0xFF000000),
        onSurface = Color(0xFFFFFFFF),
        surfaceVariant = Color(0xFF202020),
        onSurfaceVariant = Color(0xFFFFFFFF),
        outline = Color(0xFFFFFFFF),
        outlineVariant = Color(0xFF808080),
        scrim = Color(0xFF000000),
        inverseSurface = Color(0xFFFFFFFF),
        inverseOnSurface = Color(0xFF000000),
        inversePrimary = Color(0xFF000000),
        surfaceDim = Color(0xFF000000),
        surfaceBright = Color(0xFF404040),
        surfaceContainerLowest = Color(0xFF000000),
        surfaceContainerLow = Color(0xFF101010),
        surfaceContainer = Color(0xFF202020),
        surfaceContainerHigh = Color(0xFF303030),
        surfaceContainerHighest = Color(0xFF404040)
    )
    
    /**
     * Get appropriate color scheme based on system settings.
     */
    @Composable
    fun getColorScheme(
        isDarkTheme: Boolean = isSystemInDarkTheme(),
        normalLightScheme: ColorScheme = lightColorScheme(),
        normalDarkScheme: ColorScheme = darkColorScheme()
    ): ColorScheme {
        val context = LocalContext.current
        val isHighContrast = remember { isHighContrastEnabled(context) }
        
        return when {
            isHighContrast && isDarkTheme -> highContrastDarkColorScheme
            isHighContrast && !isDarkTheme -> highContrastLightColorScheme
            isDarkTheme -> normalDarkScheme
            else -> normalLightScheme
        }
    }
    
    /**
     * Enhanced button colors for high contrast mode.
     */
    @Composable
    fun getHighContrastButtonColors(
        isHighContrast: Boolean = isHighContrastEnabled(LocalContext.current),
        isDarkTheme: Boolean = isSystemInDarkTheme()
    ): ButtonColors {
        return if (isHighContrast) {
            if (isDarkTheme) {
                ButtonDefaults.buttonColors(
                    containerColor = Color.White,
                    contentColor = Color.Black,
                    disabledContainerColor = Color(0xFF404040),
                    disabledContentColor = Color(0xFF808080)
                )
            } else {
                ButtonDefaults.buttonColors(
                    containerColor = Color.Black,
                    contentColor = Color.White,
                    disabledContainerColor = Color(0xFFE0E0E0),
                    disabledContentColor = Color(0xFF808080)
                )
            }
        } else {
            ButtonDefaults.buttonColors()
        }
    }
    
    /**
     * Enhanced slider colors for high contrast mode.
     */
    @Composable
    fun getHighContrastSliderColors(
        isHighContrast: Boolean = isHighContrastEnabled(LocalContext.current),
        isDarkTheme: Boolean = isSystemInDarkTheme()
    ): SliderColors {
        return if (isHighContrast) {
            if (isDarkTheme) {
                SliderDefaults.colors(
                    thumbColor = Color.White,
                    activeTrackColor = Color.White,
                    inactiveTrackColor = Color(0xFF404040),
                    disabledThumbColor = Color(0xFF808080),
                    disabledActiveTrackColor = Color(0xFF404040),
                    disabledInactiveTrackColor = Color(0xFF202020)
                )
            } else {
                SliderDefaults.colors(
                    thumbColor = Color.Black,
                    activeTrackColor = Color.Black,
                    inactiveTrackColor = Color(0xFFE0E0E0),
                    disabledThumbColor = Color(0xFF808080),
                    disabledActiveTrackColor = Color(0xFFE0E0E0),
                    disabledInactiveTrackColor = Color(0xFFF0F0F0)
                )
            }
        } else {
            SliderDefaults.colors()
        }
    }
    
    /**
     * Enhanced switch colors for high contrast mode.
     */
    @Composable
    fun getHighContrastSwitchColors(
        isHighContrast: Boolean = isHighContrastEnabled(LocalContext.current),
        isDarkTheme: Boolean = isSystemInDarkTheme()
    ): SwitchColors {
        return if (isHighContrast) {
            if (isDarkTheme) {
                SwitchDefaults.colors(
                    checkedThumbColor = Color.Black,
                    checkedTrackColor = Color.White,
                    uncheckedThumbColor = Color.White,
                    uncheckedTrackColor = Color(0xFF404040),
                    disabledCheckedThumbColor = Color(0xFF808080),
                    disabledCheckedTrackColor = Color(0xFF404040),
                    disabledUncheckedThumbColor = Color(0xFF808080),
                    disabledUncheckedTrackColor = Color(0xFF202020)
                )
            } else {
                SwitchDefaults.colors(
                    checkedThumbColor = Color.White,
                    checkedTrackColor = Color.Black,
                    uncheckedThumbColor = Color.Black,
                    uncheckedTrackColor = Color(0xFFE0E0E0),
                    disabledCheckedThumbColor = Color(0xFF808080),
                    disabledCheckedTrackColor = Color(0xFFE0E0E0),
                    disabledUncheckedThumbColor = Color(0xFF808080),
                    disabledUncheckedTrackColor = Color(0xFFF0F0F0)
                )
            }
        } else {
            SwitchDefaults.colors()
        }
    }
}

/**
 * Composable wrapper that applies high contrast theme when needed.
 */
@Composable
fun HighContrastTheme(
    content: @Composable () -> Unit
) {
    val colorScheme = HighContrastSupport.getColorScheme()
    
    MaterialTheme(
        colorScheme = colorScheme,
        content = content
    )
}