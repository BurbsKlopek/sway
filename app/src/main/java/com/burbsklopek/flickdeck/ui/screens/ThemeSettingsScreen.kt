package com.burbsklopek.flickdeck.ui.screens

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.LocalCafe
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.burbsklopek.flickdeck.BuildConfig
import com.burbsklopek.flickdeck.model.*
import com.burbsklopek.flickdeck.ui.components.QrCodeBox
import com.burbsklopek.flickdeck.ui.theme.*

private const val DONATE_URL = "https://buymeacoffee.com/burbsklopek"
private const val DONATE_DISPLAY = "buymeacoffee.com/burbsklopek"
private val CoffeeGold = Color(0xFFFFDD00)
private val CoffeeAmber = Color(0xFFF59E0B)

@Composable
fun ThemeSettingsScreen(
    settings: AppSettings,
    onUpdateSettings: ((AppSettings) -> AppSettings) -> Unit,
    onResetPassed: () -> Unit
) {
    val theme = settings.themePreset
    var showDonateModal by remember { mutableStateOf(false) }

    Box(modifier = Modifier.fillMaxSize()) {
        // Unified Smoked-Glass Studio Folio (replaces chopped SaaS-card boxes with structural hairlines)
        Column(
            verticalArrangement = Arrangement.spacedBy(20.dp),
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 48.dp)
                .verticalScroll(rememberScrollState())
                .padding(bottom = 28.dp)
                .liquidGlass(theme, settings.glassIntensity, RoundedCornerShape(26.dp))
                .padding(horizontal = 32.dp, vertical = 26.dp)
        ) {
            // 1. Color Palette
            StudioRowSection(
                title = "Palette",
                description = settings.themePreset.subtitle
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    ThemePreset.entries.forEach { preset ->
                        PaletteSwatchTile(
                            preset = preset,
                            selected = settings.themePreset == preset,
                            onSelect = { onUpdateSettings { it.copy(themePreset = preset) } },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }

            StudioHairline()

            // 2. Typography Pairing
            StudioRowSection(
                title = "Typography",
                description = settings.fontPairing.subtitle
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    FontPairing.entries.forEach { pairing ->
                        val previewFont = remember(pairing) { resolveSwayFonts(pairing).display }
                        GlassPillButton(
                            text = pairing.label,
                            selected = settings.fontPairing == pairing,
                            accentColor = theme.primaryColor,
                            fontFamily = previewFont,
                            fontSize = 14.sp,
                            onClick = { onUpdateSettings { it.copy(fontPairing = pairing) } },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }

            StudioHairline()

            // 3. Surface, Motion & 4K Playback (clean two-column editorial split)
            Row(
                horizontalArrangement = Arrangement.spacedBy(36.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                StudioRowSection(
                    title = "Glass & atmosphere",
                    description = "Surface opacity, 4K RenderEffect backdrop blur, and poster shadow",
                    modifier = Modifier.weight(1f)
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        GlassIntensity.entries.forEach { gi ->
                            GlassPillButton(
                                text = gi.label,
                                selected = settings.glassIntensity == gi,
                                onClick = { onUpdateSettings { it.copy(glassIntensity = gi) } }
                            )
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        GlassPillButton(
                            text = if (settings.backdropBlurEnabled) "Backdrop blur on" else "Backdrop blur off",
                            selected = settings.backdropBlurEnabled,
                            accentColor = theme.secondaryColor,
                            onClick = { onUpdateSettings { it.copy(backdropBlurEnabled = !it.backdropBlurEnabled) } }
                        )
                        GlassPillButton(
                            text = if (settings.cardShadowGlow) "Poster shadow on" else "Poster shadow off",
                            selected = settings.cardShadowGlow,
                            accentColor = theme.secondaryColor,
                            onClick = { onUpdateSettings { it.copy(cardShadowGlow = !it.cardShadowGlow) } }
                        )
                    }
                }

                StudioRowSection(
                    title = "Motion & trailer resolution",
                    description = "Card spring velocity and preferred playback quality",
                    modifier = Modifier.weight(1f)
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SwipeSpeed.entries.forEach { sp ->
                            GlassPillButton(
                                text = sp.label,
                                selected = settings.swipeSpeed == sp,
                                onClick = { onUpdateSettings { it.copy(swipeSpeed = sp) } }
                            )
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        VideoQuality.entries.forEach { vq ->
                            GlassPillButton(
                                text = vq.label,
                                selected = settings.defaultVideoQuality == vq,
                                accentColor = theme.secondaryColor,
                                onClick = { onUpdateSettings { it.copy(defaultVideoQuality = vq) } }
                            )
                        }
                    }
                }
            }

            StudioHairline()

            // 4. Remote Controls & Sync Rules
            Row(
                horizontalArrangement = Arrangement.spacedBy(36.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                StudioRowSection(
                    title = "Remote controls",
                    description = settings.controlScheme.description,
                    modifier = Modifier.weight(1.1f)
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SwipeControlScheme.entries.forEach { scheme ->
                            GlassPillButton(
                                text = scheme.label,
                                selected = settings.controlScheme == scheme,
                                onClick = { onUpdateSettings { it.copy(controlScheme = scheme) } }
                            )
                        }
                    }
                }

                StudioRowSection(
                    title = "Sync & deck visibility",
                    description = "Choose which connected accounts receive swipes",
                    modifier = Modifier.weight(0.9f)
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SyncTargetMode.entries.forEach { mode ->
                            GlassPillButton(
                                text = mode.label,
                                selected = settings.syncTargetMode == mode,
                                fontSize = 12.5.sp,
                                horizontalPadding = 12.dp,
                                onClick = { onUpdateSettings { it.copy(syncTargetMode = mode) } }
                            )
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        GlassPillButton(
                            text = if (settings.hideAlreadyTracked) "Hide tracked shows" else "Show tracked shows",
                            selected = settings.hideAlreadyTracked,
                            accentColor = theme.watchedColor,
                            onClick = { onUpdateSettings { it.copy(hideAlreadyTracked = !it.hideAlreadyTracked) } }
                        )
                        GlassPillButton(
                            text = "Restore passed",
                            accentColor = theme.passColor,
                            onClick = onResetPassed
                        )
                    }
                }
            }

            StudioHairline()

            // 5. Support Sway (Buy Me a Coffee)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(42.dp)
                            .clip(RoundedCornerShape(11.dp))
                            .background(CoffeeGold.copy(alpha = 0.16f))
                            .border(1.dp, CoffeeGold.copy(alpha = 0.55f), RoundedCornerShape(11.dp))
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.LocalCafe,
                            contentDescription = null,
                            tint = CoffeeGold,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    Column {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Text(
                                text = "Support Sway",
                                color = SwayIvory,
                                fontFamily = LocalSwayFonts.current.display,
                                fontSize = 18.sp,
                                fontWeight = FontWeight.ExtraBold
                            )
                            Text(
                                text = "v${BuildConfig.VERSION_NAME}",
                                color = SwaySilver,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                        Text(
                            text = "Enjoying Sway? Support updates via Buy Me a Coffee ($DONATE_DISPLAY).",
                            color = SwaySilver,
                            fontSize = 13.sp
                        )
                    }
                }

                GlassPillButton(
                    text = "Buy Me a Coffee",
                    icon = Icons.Rounded.LocalCafe,
                    selected = true,
                    accentColor = CoffeeAmber,
                    onClick = { showDonateModal = true }
                )
            }
        }

        // ── Buy Me a Coffee 4K QR Modal ──
        AnimatedVisibility(
            visible = showDonateModal,
            enter = fadeIn() + scaleIn(
                initialScale = 0.94f,
                animationSpec = spring(stiffness = 720f, dampingRatio = 0.82f)
            ),
            exit = fadeOut() + scaleOut(targetScale = 0.96f)
        ) {
            DonateQrModal(
                onDismiss = { showDonateModal = false }
            )
        }
    }
}

@Composable
private fun StudioHairline() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(Color.White.copy(alpha = 0.08f))
    )
}

@Composable
private fun StudioRowSection(
    title: String,
    description: String,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(modifier = modifier) {
        Text(
            text = title,
            color = SwayIvory,
            fontFamily = LocalSwayFonts.current.display,
            fontSize = 18.sp,
            fontWeight = FontWeight.ExtraBold,
            letterSpacing = (-0.3).sp
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = description,
            color = SwaySilver,
            fontSize = 12.5.sp
        )
        Spacer(Modifier.height(12.dp))
        content()
    }
}

@Composable
private fun PaletteSwatchTile(
    preset: ThemePreset,
    selected: Boolean,
    onSelect: () -> Unit,
    modifier: Modifier = Modifier
) {
    val interaction = remember { MutableInteractionSource() }
    val isFocused by interaction.collectIsFocusedAsState()

    Column(
        horizontalAlignment = Alignment.Start,
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(
                Brush.linearGradient(
                    listOf(preset.bgDeepColor, preset.bgGlowColor)
                )
            )
            .border(
                width = if (isFocused) 2.dp else if (selected) 1.5.dp else 1.dp,
                color = if (isFocused) SwayIvory else if (selected) preset.primaryColor else Color.White.copy(alpha = 0.14f),
                shape = RoundedCornerShape(14.dp)
            )
            .clickable(interactionSource = interaction, indication = null, onClick = onSelect)
            .padding(horizontal = 11.dp, vertical = 12.dp)
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Box(
                Modifier
                    .size(15.dp)
                    .clip(CircleShape)
                    .background(preset.primaryColor)
            )
            Box(
                Modifier
                    .size(15.dp)
                    .clip(CircleShape)
                    .background(preset.secondaryColor)
            )
        }
        Spacer(Modifier.height(10.dp))
        Text(
            text = preset.title,
            color = SwayIvory,
            fontSize = 11.5.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
        )
        Text(
            text = if (selected) "Selected" else "Select",
            color = if (selected) preset.primaryColor else SwaySilver,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium
        )
    }
}

@Composable
private fun DonateQrModal(
    onDismiss: () -> Unit
) {
    val settings = LocalAppSettings.current
    val theme = settings.themePreset
    val context = LocalContext.current
    val initialFocus = remember { FocusRequester() }

    BackHandler(onBack = onDismiss)

    LaunchedEffect(Unit) {
        runCatching { initialFocus.requestFocus() }
    }

    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.82f))
            .modalScrimDismiss(onDismiss)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(28.dp),
            modifier = Modifier
                .width(660.dp)
                .liquidGlass(
                    theme = theme,
                    intensity = GlassIntensity.ULTRA_GLASS,
                    shape = RoundedCornerShape(26.dp),
                    isFocused = true,
                    accentOverride = CoffeeGold
                )
                .consumeModalTouches()
                .padding(30.dp)
        ) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(20.dp))
                    .border(2.dp, CoffeeGold, RoundedCornerShape(20.dp))
            ) {
                QrCodeBox(
                    content = DONATE_URL,
                    size = 184.dp
                )
            }

            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = "Buy Me a Coffee",
                    color = SwayIvory,
                    fontFamily = LocalSwayFonts.current.display,
                    fontSize = 26.sp,
                    fontWeight = FontWeight.ExtraBold
                )

                Text(
                    text = "Scan the QR code with your phone camera to support Sway updates and new features.",
                    color = SwaySilver,
                    fontSize = 14.sp,
                    lineHeight = 20.sp
                )

                Text(
                    text = DONATE_DISPLAY,
                    color = CoffeeGold,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold
                )

                Spacer(Modifier.height(4.dp))

                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    GlassPillButton(
                        text = "Open in browser",
                        icon = Icons.AutoMirrored.Rounded.OpenInNew,
                        selected = true,
                        accentColor = CoffeeAmber,
                        onClick = {
                            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(DONATE_URL)).apply {
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            }
                            runCatching { context.startActivity(intent) }.onFailure {
                                Toast.makeText(
                                    context,
                                    "Scan the QR code with your phone camera ($DONATE_DISPLAY)",
                                    Toast.LENGTH_LONG
                                ).show()
                            }
                        },
                        modifier = Modifier.focusRequester(initialFocus)
                    )

                    GlassPillButton(
                        text = "Close",
                        onClick = onDismiss
                    )
                }
            }
        }
    }
}
