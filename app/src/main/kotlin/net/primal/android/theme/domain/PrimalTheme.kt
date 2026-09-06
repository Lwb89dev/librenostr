package net.primal.android.theme.domain

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import net.primal.android.R
import net.primal.android.theme.PixelShapes
import net.primal.android.theme.PixelTypography
import net.primal.android.theme.PrimalShapes
import net.primal.android.theme.PrimalTypography
import net.primal.android.theme.colors.darkPixelColorScheme
import net.primal.android.theme.colors.darkPixelExtraColorScheme
import net.primal.android.theme.colors.ExtraColorScheme
import net.primal.android.theme.colors.fireColorScheme
import net.primal.android.theme.colors.fireExtraColorScheme
import net.primal.android.theme.colors.fireLightColorScheme
import net.primal.android.theme.colors.fireLightExtraColorScheme
import net.primal.android.theme.colors.darkGreenPixelColorScheme
import net.primal.android.theme.colors.darkGreenPixelExtraColorScheme
import net.primal.android.theme.colors.greenPixelColorScheme
import net.primal.android.theme.colors.greenPixelExtraColorScheme
import net.primal.android.theme.colors.iceColorScheme
import net.primal.android.theme.colors.iceExtraColorScheme
import net.primal.android.theme.colors.lightPixelColorScheme
import net.primal.android.theme.colors.lightPixelExtraColorScheme
import net.primal.android.theme.colors.midnightColorScheme
import net.primal.android.theme.colors.midnightExtraColorScheme

enum class PrimalTheme(
    val themeName: String,
    val colorScheme: ColorScheme,
    val extraColorScheme: ExtraColorScheme,
    val isDarkTheme: Boolean,
    val logoId: Int,
    val displayName: String,
    val typography: Typography = PrimalTypography,
    val shapes: Shapes = PrimalShapes,
    val isPixelTheme: Boolean = false,
) {
    Midnight(
        themeName = "midnight",
        colorScheme = midnightColorScheme,
        extraColorScheme = midnightExtraColorScheme,
        isDarkTheme = true,
        logoId = R.drawable.primal_icon,
        displayName = "Midnight Relay",
    ),

    Ice(
        themeName = "ice",
        colorScheme = iceColorScheme,
        extraColorScheme = iceExtraColorScheme,
        isDarkTheme = false,
        logoId = R.drawable.primal_icon,
        displayName = "Ice Relay",
    ),

    DarkPixel(
        themeName = "dark-pixel",
        colorScheme = darkPixelColorScheme,
        extraColorScheme = darkPixelExtraColorScheme,
        isDarkTheme = true,
        logoId = R.drawable.primal_icon,
        displayName = "Dark Pixel",
        typography = PixelTypography,
        shapes = PixelShapes,
        isPixelTheme = true,
    ),

    LightPixel(
        themeName = "light-pixel",
        colorScheme = lightPixelColorScheme,
        extraColorScheme = lightPixelExtraColorScheme,
        isDarkTheme = false,
        logoId = R.drawable.primal_icon,
        displayName = "Light Pixel",
        typography = PixelTypography,
        shapes = PixelShapes,
        isPixelTheme = true,
    ),

    GreenPixel(
        themeName = "green-pixel",
        colorScheme = greenPixelColorScheme,
        extraColorScheme = greenPixelExtraColorScheme,
        isDarkTheme = false,
        logoId = R.drawable.primal_icon,
        displayName = "Green Pixel",
        typography = PixelTypography,
        shapes = PixelShapes,
        isPixelTheme = true,
    ),

    DarkGreenPixel(
        themeName = "dark-green-pixel",
        colorScheme = darkGreenPixelColorScheme,
        extraColorScheme = darkGreenPixelExtraColorScheme,
        isDarkTheme = true,
        logoId = R.drawable.primal_icon,
        displayName = "Dark Green Pixel",
        typography = PixelTypography,
        shapes = PixelShapes,
        isPixelTheme = true,
    ),

    Fire(
        themeName = "fire",
        colorScheme = fireColorScheme,
        extraColorScheme = fireExtraColorScheme,
        isDarkTheme = true,
        logoId = R.drawable.primal_icon,
        displayName = "Fire",
    ),

    FireLight(
        themeName = "fire-light",
        colorScheme = fireLightColorScheme,
        extraColorScheme = fireLightExtraColorScheme,
        isDarkTheme = false,
        logoId = R.drawable.primal_icon,
        displayName = "Fire Light",
    ),
    ;

    val inverse: PrimalTheme
        get() = when (this) {
            Midnight -> Ice
            Ice -> Midnight
            DarkPixel -> LightPixel
            LightPixel -> DarkPixel
            GreenPixel -> DarkGreenPixel
            DarkGreenPixel -> GreenPixel
            Fire -> FireLight
            FireLight -> Fire
        }

    companion object {
        fun valueOf(themeName: String): PrimalTheme? {
            return when (themeName) {
                // Migration: map removed themes to their closest remaining equivalents
                // for users who still have these values persisted in DataStore.
                "sunset" -> Midnight
                "sunrise" -> Ice
                // Keep the original persisted identifiers valid while allowing
                // the new display names to evolve independently.
                "midnight-relay" -> Midnight
                "ice-relay" -> Ice
                else -> enumValues<PrimalTheme>().find { it.themeName == themeName }
            }
        }
    }
}
