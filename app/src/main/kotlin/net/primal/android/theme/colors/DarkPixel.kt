package net.primal.android.theme.colors

import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.graphics.Color

private val darkPixelBrandText = Color(0xFFE9E0CF)
private val darkPixelBackground1 = Color(0xFF101114)
private val darkPixelBackground2 = Color(0xFF15181C)
private val darkPixelBackground3 = Color(0xFF2A2E34)
private val darkPixelBackground4 = Color(0xFF181B20)
private val darkPixelBackground5 = Color(0xFF202329)
private val darkPixelForeground1 = Color(0xFFF3EBDD)
private val darkPixelForeground2 = Color(0xFFA9B0B8)
private val darkPixelForeground3 = Color(0xFF7C858E)
private val darkPixelForeground4 = Color(0xFF5A626B)
private val darkPixelForeground5 = Color(0xFF444A51)
private val darkPixelAccent0 = Color(0xFFFF8A4C)
private val darkPixelAccent1 = Color(0xFF4DD6C8)
private val darkPixelAccent2 = Color(0xFFF4D06F)
private val darkPixelSuccessBright = Color(0xFF7FD6A5)
private val darkPixelSuccessDim = Color(0xFF103629)
private val darkPixelWarningBright = Color(0xFFFF6B5B)
private val darkPixelWarningDim = Color(0xFF5A2418)
private val darkPixelReplied = Color(0xFFB7B0A2)
private val darkPixelZapped = Color(0xFFFF8A4C)
private val darkPixelLiked = Color(0xFFFF6EA8)
private val darkPixelReposted = Color(0xFF4DD6C8)
private val darkPixelBookmarked = Color(0xFFF4D06F)

val darkPixelColorScheme = darkColorScheme(
    primary = darkPixelAccent0,
    onPrimary = Color(0xFF2A1208),
    primaryContainer = Color(0xFF5B2B18),
    onPrimaryContainer = Color(0xFFFFDBCB),
    secondary = darkPixelAccent1,
    onSecondary = Color(0xFF08201E),
    secondaryContainer = Color(0xFF164B46),
    onSecondaryContainer = Color(0xFFB8FFF7),
    tertiary = darkPixelAccent2,
    onTertiary = Color(0xFF211B08),
    tertiaryContainer = Color(0xFF554610),
    onTertiaryContainer = Color(0xFFFFE9A6),
    background = darkPixelBackground1,
    onBackground = darkPixelForeground1,
    surface = darkPixelBackground1,
    onSurface = darkPixelForeground1,
    surfaceVariant = darkPixelBackground2,
    onSurfaceVariant = darkPixelForeground2,
    error = darkPixelWarningBright,
    onError = Color(0xFF2C0502),
    errorContainer = darkPixelWarningDim,
    onErrorContainer = Color(0xFFFFDAD5),
    outline = darkPixelBackground3,
    outlineVariant = Color(0xFF24282D),
)

val darkPixelExtraColorScheme = extraColorScheme(
    onBrand = darkPixelBrandText,
    surfaceVariantAlt1 = darkPixelBackground3,
    surfaceVariantAlt2 = darkPixelBackground4,
    surfaceVariantAlt3 = darkPixelBackground5,
    onSurfaceVariantAlt1 = darkPixelForeground2,
    onSurfaceVariantAlt2 = darkPixelForeground3,
    onSurfaceVariantAlt3 = darkPixelForeground4,
    onSurfaceVariantAlt4 = darkPixelForeground5,
    warning = darkPixelWarningDim,
    successBright = darkPixelSuccessBright,
    successDim = darkPixelSuccessDim,
    replied = darkPixelReplied,
    zapped = darkPixelZapped,
    liked = darkPixelLiked,
    reposted = darkPixelReposted,
    bookmarked = darkPixelBookmarked,
)

val lightPixelColorScheme = androidx.compose.material3.lightColorScheme(
    primary = Color(0xFFB94E22),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFDBCA),
    onPrimaryContainer = Color(0xFF3D0E00),
    secondary = Color(0xFF147C76),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFF9CF2E8),
    onSecondaryContainer = Color(0xFF00201D),
    tertiary = Color(0xFF6D5E3F),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFF2E2B9),
    onTertiaryContainer = Color(0xFF241A04),
    background = Color(0xFFF5F2EA),
    onBackground = Color(0xFF1D2628),
    surface = Color(0xFFF5F2EA),
    onSurface = Color(0xFF1D2628),
    surfaceVariant = Color(0xFFFFFCF4),
    onSurfaceVariant = Color(0xFF5D665F),
    error = Color(0xFFB3261E),
    onError = Color.White,
    errorContainer = Color(0xFFFFDAD4),
    onErrorContainer = Color(0xFF410002),
    outline = Color(0xFFD9D1C3),
    outlineVariant = Color(0xFFE7E0D4),
)

val lightPixelExtraColorScheme = extraColorScheme(
    onBrand = Color(0xFF1D2628),
    surfaceVariantAlt1 = Color(0xFFDAD4C8),
    surfaceVariantAlt2 = Color(0xFFF8F4EA),
    surfaceVariantAlt3 = Color(0xFFEEE8DD),
    onSurfaceVariantAlt1 = Color(0xFF1D2628),
    onSurfaceVariantAlt2 = Color(0xFF5D665F),
    onSurfaceVariantAlt3 = Color(0xFF7C857E),
    onSurfaceVariantAlt4 = Color(0xFF98A096),
    warning = Color(0xFFFFE0D4),
    successBright = Color(0xFF287A55),
    successDim = Color(0xFFC5E8D4),
    replied = Color(0xFF59605C),
    zapped = Color(0xFFC45B24),
    liked = Color(0xFFC12C66),
    reposted = Color(0xFF287A68),
    bookmarked = Color(0xFF3977A4),
)
