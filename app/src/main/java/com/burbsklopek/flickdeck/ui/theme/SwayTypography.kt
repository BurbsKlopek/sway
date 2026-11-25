package com.burbsklopek.flickdeck.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.burbsklopek.flickdeck.R
import com.burbsklopek.flickdeck.model.FontPairing

@OptIn(ExperimentalTextApi::class)
private fun variableFont(
    resId: Int,
    weight: FontWeight,
    wghtValue: Int,
    extraSettings: List<FontVariation.Setting> = emptyList()
): Font = Font(
    resId = resId,
    weight = weight,
    variationSettings = FontVariation.Settings(
        *(listOf(FontVariation.weight(wghtValue)) + extraSettings).toTypedArray()
    )
)

val PlusJakartaSansFamily = FontFamily(
    variableFont(R.font.plus_jakarta_sans_var, FontWeight.Normal, 450),
    variableFont(R.font.plus_jakarta_sans_var, FontWeight.Medium, 540),
    variableFont(R.font.plus_jakarta_sans_var, FontWeight.SemiBold, 640),
    variableFont(R.font.plus_jakarta_sans_var, FontWeight.Bold, 730),
    variableFont(R.font.plus_jakarta_sans_var, FontWeight.ExtraBold, 800),
    variableFont(R.font.plus_jakarta_sans_var, FontWeight.Black, 800)
)

val SyneDisplayFamily = FontFamily(
    variableFont(R.font.syne_var, FontWeight.SemiBold, 650),
    variableFont(R.font.syne_var, FontWeight.Bold, 740),
    variableFont(R.font.syne_var, FontWeight.ExtraBold, 800),
    variableFont(R.font.syne_var, FontWeight.Black, 800)
)

val OutfitDisplayFamily = FontFamily(
    variableFont(R.font.outfit_var, FontWeight.SemiBold, 600),
    variableFont(R.font.outfit_var, FontWeight.Bold, 740),
    variableFont(R.font.outfit_var, FontWeight.ExtraBold, 840),
    variableFont(R.font.outfit_var, FontWeight.Black, 900)
)

val SpaceGroteskDisplayFamily = FontFamily(
    variableFont(R.font.space_grotesk_var, FontWeight.SemiBold, 600),
    variableFont(R.font.space_grotesk_var, FontWeight.Bold, 700),
    variableFont(R.font.space_grotesk_var, FontWeight.ExtraBold, 700),
    variableFont(R.font.space_grotesk_var, FontWeight.Black, 700)
)

@OptIn(ExperimentalTextApi::class)
val FrauncesDisplayFamily = FontFamily(
    variableFont(
        R.font.fraunces_var,
        FontWeight.Bold,
        720,
        listOf(FontVariation.Setting("SOFT", 60f), FontVariation.Setting("opsz", 48f))
    ),
    variableFont(
        R.font.fraunces_var,
        FontWeight.ExtraBold,
        820,
        listOf(FontVariation.Setting("SOFT", 60f), FontVariation.Setting("opsz", 60f))
    ),
    variableFont(
        R.font.fraunces_var,
        FontWeight.Black,
        880,
        listOf(FontVariation.Setting("SOFT", 50f), FontVariation.Setting("opsz", 72f))
    )
)

@Immutable
data class SwayFontSet(
    val display: FontFamily,
    val body: FontFamily,
    val titleTrackingSp: Float
)

fun resolveSwayFonts(pairing: FontPairing?): SwayFontSet = when (pairing ?: FontPairing.OUTFIT_GEOMETRIC) {
    FontPairing.OUTFIT_GEOMETRIC -> SwayFontSet(
        display = OutfitDisplayFamily,
        body = PlusJakartaSansFamily,
        titleTrackingSp = -0.7f
    )
    FontPairing.FRAUNCES_SERIF -> SwayFontSet(
        display = FrauncesDisplayFamily,
        body = PlusJakartaSansFamily,
        titleTrackingSp = -0.4f
    )
    FontPairing.CINEMA_SYNE -> SwayFontSet(
        display = SyneDisplayFamily,
        body = PlusJakartaSansFamily,
        titleTrackingSp = -0.8f
    )
    FontPairing.SPACE_GROTESK -> SwayFontSet(
        display = SpaceGroteskDisplayFamily,
        body = PlusJakartaSansFamily,
        titleTrackingSp = -0.9f
    )
}

val LocalSwayFonts = compositionLocalOf { resolveSwayFonts(FontPairing.OUTFIT_GEOMETRIC) }

fun buildSwayMaterialTypography(fonts: SwayFontSet): Typography = Typography(
    displayLarge = TextStyle(fontFamily = fonts.display, fontWeight = FontWeight.ExtraBold, fontSize = 38.sp),
    displayMedium = TextStyle(fontFamily = fonts.display, fontWeight = FontWeight.ExtraBold, fontSize = 32.sp),
    headlineLarge = TextStyle(fontFamily = fonts.display, fontWeight = FontWeight.ExtraBold, fontSize = 26.sp),
    headlineMedium = TextStyle(fontFamily = fonts.display, fontWeight = FontWeight.Bold, fontSize = 22.sp),
    titleLarge = TextStyle(fontFamily = fonts.display, fontWeight = FontWeight.Bold, fontSize = 19.sp),
    titleMedium = TextStyle(fontFamily = fonts.body, fontWeight = FontWeight.Bold, fontSize = 15.sp),
    bodyLarge = TextStyle(fontFamily = fonts.body, fontWeight = FontWeight.Medium, fontSize = 15.sp),
    bodyMedium = TextStyle(fontFamily = fonts.body, fontWeight = FontWeight.Medium, fontSize = 13.5.sp),
    labelLarge = TextStyle(fontFamily = fonts.body, fontWeight = FontWeight.Bold, fontSize = 13.sp),
    labelMedium = TextStyle(fontFamily = fonts.body, fontWeight = FontWeight.SemiBold, fontSize = 11.5.sp)
)
