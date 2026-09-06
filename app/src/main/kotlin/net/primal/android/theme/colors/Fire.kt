package net.primal.android.theme.colors

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

private val fireDarkBackground = Color(0xFF120B0A)
private val fireDarkSurface = Color(0xFF1A0E0B)
private val fireDarkCard = Color(0xFF21120F)
private val fireDarkRaised = Color(0xFF2D1712)
private val fireDarkText = Color(0xFFFFF0E4)
private val fireDarkMuted = Color(0xFFD1A793)
private val fireDarkSubtle = Color(0xFFA06F5E)
private val fireDarkFaint = Color(0xFF73483A)
private val fireDarkPrimary = Color(0xFFFF7A1A)
private val fireDarkSecondary = Color(0xFFFF3D2E)
private val fireDarkTertiary = Color(0xFFFFB000)

val fireColorScheme = darkColorScheme(
    primary = fireDarkPrimary,
    onPrimary = Color(0xFF3A0B00),
    primaryContainer = Color(0xFF6B1C08),
    onPrimaryContainer = Color(0xFFFFDBCB),
    secondary = fireDarkSecondary,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFF741115),
    onSecondaryContainer = Color(0xFFFFDAD6),
    tertiary = fireDarkTertiary,
    onTertiary = Color(0xFF2B1D00),
    tertiaryContainer = Color(0xFF5B4000),
    onTertiaryContainer = Color(0xFFFFE18A),
    background = fireDarkBackground,
    onBackground = fireDarkText,
    surface = fireDarkBackground,
    onSurface = fireDarkText,
    surfaceVariant = fireDarkSurface,
    onSurfaceVariant = fireDarkMuted,
    error = Color(0xFFFF6B4A),
    onError = Color(0xFF3B0800),
    errorContainer = Color(0xFF642014),
    onErrorContainer = Color(0xFFFFDAD2),
    outline = Color(0xFF4A251A),
    outlineVariant = Color(0xFF321A13),
)

val fireExtraColorScheme = extraColorScheme(
    onBrand = fireDarkText,
    surfaceVariantAlt1 = Color(0xFF4A251A),
    surfaceVariantAlt2 = fireDarkCard,
    surfaceVariantAlt3 = fireDarkRaised,
    onSurfaceVariantAlt1 = fireDarkMuted,
    onSurfaceVariantAlt2 = fireDarkSubtle,
    onSurfaceVariantAlt3 = fireDarkFaint,
    onSurfaceVariantAlt4 = Color(0xFF523126),
    warning = Color(0xFF5F2115),
    successBright = Color(0xFF8BE28E),
    successDim = Color(0xFF173C20),
    replied = Color(0xFFD8B4A0),
    zapped = Color(0xFFFF9D1F),
    liked = Color(0xFFFF5E76),
    reposted = Color(0xFFFFB000),
    bookmarked = Color(0xFF4DB7FF),
)

val fireLightColorScheme = lightColorScheme(
    primary = Color(0xFFC94318),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFDBCB),
    onPrimaryContainer = Color(0xFF3D0B00),
    secondary = Color(0xFFD9271C),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFFFDAD5),
    onSecondaryContainer = Color(0xFF410003),
    tertiary = Color(0xFFC97900),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFFDDB2),
    onTertiaryContainer = Color(0xFF2A1700),
    background = Color(0xFFFFF7EF),
    onBackground = Color(0xFF2A1510),
    surface = Color(0xFFFFF7EF),
    onSurface = Color(0xFF2A1510),
    surfaceVariant = Color(0xFFFFFDF9),
    onSurfaceVariant = Color(0xFF76584D),
    error = Color(0xFFBA1A1A),
    onError = Color.White,
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    outline = Color(0xFFF1CDB3),
    outlineVariant = Color(0xFFF7DED0),
)

val fireLightExtraColorScheme = extraColorScheme(
    onBrand = Color(0xFF2A1510),
    surfaceVariantAlt1 = Color(0xFFF1CDB3),
    surfaceVariantAlt2 = Color(0xFFFFF1E7),
    surfaceVariantAlt3 = Color(0xFFFFE5D5),
    onSurfaceVariantAlt1 = Color(0xFF2A1510),
    onSurfaceVariantAlt2 = Color(0xFF76584D),
    onSurfaceVariantAlt3 = Color(0xFF9B7768),
    onSurfaceVariantAlt4 = Color(0xFFB6907F),
    warning = Color(0xFFFFE0D5),
    successBright = Color(0xFF2E7D32),
    successDim = Color(0xFFC7E8C8),
    replied = Color(0xFF795548),
    zapped = Color(0xFFC95B18),
    liked = Color(0xFFC52C4B),
    reposted = Color(0xFFB57400),
    bookmarked = Color(0xFF246B9B),
)
