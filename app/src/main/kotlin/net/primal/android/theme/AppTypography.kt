package net.primal.android.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import net.primal.android.R

val LibreSansFontFamily = FontFamily(
    Font(resId = R.font.ubuntu_sans_variable, weight = FontWeight.Light, style = FontStyle.Normal),
    Font(resId = R.font.ubuntu_sans_italic_variable, weight = FontWeight.Light, style = FontStyle.Italic),
    Font(resId = R.font.ubuntu_sans_variable, weight = FontWeight.Normal, style = FontStyle.Normal),
    Font(resId = R.font.ubuntu_sans_italic_variable, weight = FontWeight.Normal, style = FontStyle.Italic),
    Font(resId = R.font.ubuntu_sans_variable, weight = FontWeight.Medium, style = FontStyle.Normal),
    Font(resId = R.font.ubuntu_sans_italic_variable, weight = FontWeight.Medium, style = FontStyle.Italic),
    Font(resId = R.font.ubuntu_sans_variable, weight = FontWeight.SemiBold, style = FontStyle.Normal),
    Font(resId = R.font.ubuntu_sans_italic_variable, weight = FontWeight.SemiBold, style = FontStyle.Italic),
    Font(resId = R.font.ubuntu_sans_variable, weight = FontWeight.Bold, style = FontStyle.Normal),
    Font(resId = R.font.ubuntu_sans_italic_variable, weight = FontWeight.Bold, style = FontStyle.Italic),
    Font(resId = R.font.ubuntu_sans_variable, weight = FontWeight.ExtraBold, style = FontStyle.Normal),
    Font(resId = R.font.ubuntu_sans_italic_variable, weight = FontWeight.ExtraBold, style = FontStyle.Italic),
)

val CourierPrimeFontFamily = FontFamily(
    Font(resId = R.font.courier_prime_regular, weight = FontWeight.Normal, style = FontStyle.Normal),
    Font(resId = R.font.courier_prime_italic, weight = FontWeight.Normal, style = FontStyle.Italic),
    Font(resId = R.font.courier_prime_bold, weight = FontWeight.Bold, style = FontStyle.Normal),
    Font(resId = R.font.courier_prime_bold_italic, weight = FontWeight.Bold, style = FontStyle.Italic),
)

val PrimalTypography = Typography(
    bodyLarge = TextStyle(
        fontFamily = LibreSansFontFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 18.sp,
        lineHeight = 22.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = LibreSansFontFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 15.sp,
        lineHeight = 20.sp,
    ),
    bodySmall = TextStyle(
        fontFamily = LibreSansFontFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 18.sp,
    ),
    titleLarge = TextStyle(
        fontFamily = LibreSansFontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 20.sp,
        lineHeight = 28.sp,
    ),
    titleMedium = TextStyle(
        fontFamily = LibreSansFontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        lineHeight = 24.sp,
    ),
    titleSmall = TextStyle(
        fontFamily = LibreSansFontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp,
        lineHeight = 20.sp,
    ),
    labelLarge = TextStyle(
        fontFamily = LibreSansFontFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 15.sp,
        lineHeight = 20.sp,
    ),
    labelMedium = TextStyle(
        fontFamily = LibreSansFontFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 16.sp,
    ),
    labelSmall = TextStyle(
        fontFamily = LibreSansFontFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 13.sp,
        lineHeight = 16.sp,
    ),
    headlineLarge = TextStyle(
        fontFamily = LibreSansFontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 32.sp,
        lineHeight = 40.sp,
    ),
    headlineMedium = TextStyle(
        fontFamily = LibreSansFontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 28.sp,
        lineHeight = 36.sp,
    ),
    headlineSmall = TextStyle(
        fontFamily = LibreSansFontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 24.sp,
        lineHeight = 32.sp,
    ),
    displayLarge = TextStyle(
        fontFamily = LibreSansFontFamily,
        fontWeight = FontWeight.Bold,
        fontSize = 56.sp,
        lineHeight = 64.sp,
    ),
    displayMedium = TextStyle(
        fontFamily = LibreSansFontFamily,
        fontWeight = FontWeight.Bold,
        fontSize = 48.sp,
        lineHeight = 52.sp,
    ),
    displaySmall = TextStyle(
        fontFamily = LibreSansFontFamily,
        fontWeight = FontWeight.Bold,
        fontSize = 32.sp,
        lineHeight = 44.sp,
    ),
)
