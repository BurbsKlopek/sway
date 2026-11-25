package com.burbsklopek.flickdeck.model

import androidx.compose.ui.graphics.Color

enum class ThemePreset(
    val title: String,
    val subtitle: String,
    val bgDeep: Long,
    val bgMid: Long,
    val bgGlow: Long,
    val accentPrimary: Long,
    val accentSecondary: Long,
    val cardTint: Long,
    val watchedAccent: Long,
    val toWatchAccent: Long,
    val passAccent: Long
) {
    AURORA_PRISM(
        title = "Aurora Prism",
        subtitle = "Nocturne emerald-slate glass with prism teal & champagne gold",
        bgDeep = 0xFF050B0C,
        bgMid = 0xFF0C181A,
        bgGlow = 0xFF123532,
        accentPrimary = 0xFF2DD4BF,
        accentSecondary = 0xFFF5C451,
        cardTint = 0xFF0D191B,
        watchedAccent = 0xFFF5C451,
        toWatchAccent = 0xFF2DD4BF,
        passAccent = 0xFFF43F5E
    ),
    MIDNIGHT_NEBULA(
        title = "Midnight Nebula",
        subtitle = "Obsidian cosmos with electric violet & icy cyan glass",
        bgDeep = 0xFF070711,
        bgMid = 0xFF110F24,
        bgGlow = 0xFF25184E,
        accentPrimary = 0xFFA855F7,
        accentSecondary = 0xFF38BDF8,
        cardTint = 0xFF121126,
        watchedAccent = 0xFFF5C451,
        toWatchAccent = 0xFF38BDF8,
        passAccent = 0xFFFB7185
    ),
    CRIMSON_VELVET(
        title = "Crimson Velvet",
        subtitle = "Dark screening-room ruby with warm coral & amber light",
        bgDeep = 0xFF0C0508,
        bgMid = 0xFF1D0A12,
        bgGlow = 0xFF421024,
        accentPrimary = 0xFFF43F5E,
        accentSecondary = 0xFFFBBF24,
        cardTint = 0xFF1A0B13,
        watchedAccent = 0xFF34D399,
        toWatchAccent = 0xFFFBBF24,
        passAccent = 0xFFF43F5E
    ),
    OBSIDIAN_GOLD(
        title = "Obsidian Gold",
        subtitle = "Pure 4K OLED blackout with warm champagne & bronze",
        bgDeep = 0xFF040405,
        bgMid = 0xFF11100D,
        bgGlow = 0xFF2B2212,
        accentPrimary = 0xFFF5C451,
        accentSecondary = 0xFFFDE68A,
        cardTint = 0xFF13110E,
        watchedAccent = 0xFFF5C451,
        toWatchAccent = 0xFF38BDF8,
        passAccent = 0xFFEF4444
    ),
    ARCTIC_AURORA(
        title = "Arctic Sapphire",
        subtitle = "Deep glacial navy with crisp sky blue & periwinkle",
        bgDeep = 0xFF040A14,
        bgMid = 0xFF0A172B,
        bgGlow = 0xFF123054,
        accentPrimary = 0xFF38BDF8,
        accentSecondary = 0xFF818CF8,
        cardTint = 0xFF0B192E,
        watchedAccent = 0xFF4ADE80,
        toWatchAccent = 0xFF38BDF8,
        passAccent = 0xFFFB7185
    ),
    Y2K_CHROME(
        title = "Titanium Neon",
        subtitle = "Smoked dark titanium with magenta & cyan refraction",
        bgDeep = 0xFF080910,
        bgMid = 0xFF141626,
        bgGlow = 0xFF2D1942,
        accentPrimary = 0xFFEC4899,
        accentSecondary = 0xFF22D3EE,
        cardTint = 0xFF151726,
        watchedAccent = 0xFFE879F9,
        toWatchAccent = 0xFF22D3EE,
        passAccent = 0xFFF43F5E
    );

    val primaryColor get() = Color(accentPrimary)
    val secondaryColor get() = Color(accentSecondary)
    val bgDeepColor get() = Color(bgDeep)
    val bgMidColor get() = Color(bgMid)
    val bgGlowColor get() = Color(bgGlow)
    val cardTintColor get() = Color(cardTint)
    val watchedColor get() = Color(watchedAccent)
    val toWatchColor get() = Color(toWatchAccent)
    val passColor get() = Color(passAccent)
}

enum class GlassIntensity(val label: String, val fillAlpha: Float, val borderAlpha: Float) {
    SUBTLE("Smoked", 0.64f, 0.14f),
    FROSTED("Frosted", 0.78f, 0.24f),
    ULTRA_GLASS("Crystal", 0.88f, 0.36f)
}

enum class SwipeSpeed(val label: String, val stiffness: Float, val damping: Float) {
    SNAPPY("Snappy", 860f, 0.86f),
    FLUID("Fluid", 560f, 0.84f),
    CINEMATIC("Measured", 380f, 0.82f)
}

enum class SwipeControlScheme(val label: String, val description: String) {
    TINDER_3WAY(
        "Three-way remote",
        "Right adds to Watchlist, Up marks Watched, Left passes, Down rewinds, OK opens details"
    ),
    BINARY_SPLIT(
        "Split left & right",
        "Right marks Watched, Left adds to Watchlist, Down passes, OK opens details"
    ),
    BUTTON_DOCK(
        "Focus buttons",
        "Standard remote navigation across the action buttons below the synopsis"
    )
}

enum class VideoQuality(val label: String, val heightPx: Int, val youtubeVq: String) {
    UHD_2160P("2160p 4K", 2160, "hd2160"),
    QHD_1440P("1440p", 1440, "hd1440"),
    FHD_1080P("1080p", 1080, "hd1080"),
    AUTO("Auto", 2160, "hd2160")
}

enum class SyncTargetMode(val label: String) {
    ALL_CONNECTED("Both services"),
    TRAKT_ONLY("Trakt only"),
    SIMKL_ONLY("Simkl only")
}

enum class FontPairing(val label: String, val subtitle: String) {
    OUTFIT_GEOMETRIC("Outfit Display", "Bold architectural cinema titles paired with Plus Jakarta Sans"),
    FRAUNCES_SERIF("Fraunces Serif", "Warm Criterion-inspired optical serif paired with Plus Jakarta Sans"),
    CINEMA_SYNE("Syne Wide", "Avant-garde extended marquee titles paired with Plus Jakarta Sans"),
    SPACE_GROTESK("Space Grotesk", "Crisp technical neo-grotesque titles paired with Plus Jakarta Sans")
}

data class AppSettings(
    val themePreset: ThemePreset = ThemePreset.AURORA_PRISM,
    val fontPairing: FontPairing = FontPairing.OUTFIT_GEOMETRIC,
    val glassIntensity: GlassIntensity = GlassIntensity.FROSTED,
    val backdropBlurEnabled: Boolean = true,
    val cardShadowGlow: Boolean = true,
    val swipeSpeed: SwipeSpeed = SwipeSpeed.SNAPPY,
    val controlScheme: SwipeControlScheme = SwipeControlScheme.TINDER_3WAY,
    val defaultVideoQuality: VideoQuality = VideoQuality.UHD_2160P,
    val syncTargetMode: SyncTargetMode = SyncTargetMode.ALL_CONNECTED,
    val hideAlreadyTracked: Boolean = true
)
