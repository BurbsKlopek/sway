package com.burbsklopek.flickdeck.ui.theme

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import coil.size.Scale
import coil.size.Size
import coil.transform.Transformation
import com.burbsklopek.flickdeck.model.AppSettings
import com.burbsklopek.flickdeck.model.GlassIntensity
import com.burbsklopek.flickdeck.model.ThemePreset

val LocalAppSettings = compositionLocalOf { AppSettings() }

val SwayIvory = Color(0xFFF4F6F8)
val SwaySilver = Color(0xFF94A3B8)
val SwayObsidianInk = Color(0xFF070B0E)

/**
 * Pointer-only backdrop scrim dismiss modifier: handles mouse/touch taps outside a modal
 * WITHOUT adding a D-Pad FocusTargetNode or intercepting TV remote KeyUp events.
 */
fun Modifier.modalScrimDismiss(onDismiss: () -> Unit): Modifier =
    this.pointerInput(onDismiss) {
        detectTapGestures(onTap = { onDismiss() })
    }

/**
 * Absorbs pointer/touch taps inside a modal card so they do not bubble to modalScrimDismiss.
 */
fun Modifier.consumeModalTouches(): Modifier =
    this.pointerInput(Unit) {
        detectTapGestures(onTap = {})
    }

/**
 * Distinct structural radii by hierarchy:
 * - Large glass folios & modals: 26.dp
 * - Physical poster cards: 18.dp
 * - Tactile interactive controls: 12.dp (replaces generic 50% stadium pills everywhere)
 */
fun Modifier.liquidGlass(
    theme: ThemePreset,
    intensity: GlassIntensity,
    shape: Shape = RoundedCornerShape(26.dp),
    isFocused: Boolean = false,
    accentOverride: Color? = null,
    enableShadow: Boolean = true
): Modifier {
    val activeAccent = accentOverride ?: theme.primaryColor
    val borderBrush = if (isFocused) {
        Brush.linearGradient(
            listOf(
                SwayIvory.copy(alpha = 0.90f),
                activeAccent.copy(alpha = 0.85f),
                theme.secondaryColor.copy(alpha = 0.55f)
            )
        )
    } else {
        Brush.linearGradient(
            listOf(
                Color.White.copy(alpha = intensity.borderAlpha),
                Color.White.copy(alpha = intensity.borderAlpha * 0.30f),
                activeAccent.copy(alpha = intensity.borderAlpha * 0.25f)
            )
        )
    }

    val surfaceBrush = Brush.verticalGradient(
        listOf(
            theme.cardTintColor.copy(alpha = (intensity.fillAlpha + if (isFocused) 0.08f else 0f).coerceAtMost(0.95f)),
            theme.bgDeepColor.copy(alpha = (intensity.fillAlpha + 0.06f).coerceAtMost(0.96f))
        )
    )

    val shadowMod = if (enableShadow) {
        Modifier.shadow(
            elevation = if (isFocused) 16.dp else 10.dp,
            shape = shape,
            ambientColor = if (isFocused) activeAccent.copy(alpha = 0.50f) else Color.Black,
            spotColor = if (isFocused) activeAccent.copy(alpha = 0.58f) else Color.Black
        )
    } else {
        Modifier
    }

    return this
        .then(shadowMod)
        .clip(shape)
        .background(surfaceBrush, shape)
        .border(
            width = if (isFocused) 1.6.dp else 1.dp,
            brush = borderBrush,
            shape = shape
        )
}

/**
 * Ultra-fast off-thread 2-pass separable box blur on a tiny pre-downscaled 160x90 bitmap.
 * Cached once in Coil's MemoryCache (~57 KB texture) so the TV GPU draws the ambient aura
 * as a single <0.3ms hardware bilinear blit instead of running a 44dp RenderEffect shader
 * across 4K pixels every frame (which stalled Mali Skia OpenGL to ~5s/frame).
 */
class FastAuraBlurTransformation(private val radius: Int = 7) : Transformation {
    override val cacheKey: String = "sway_aura_blur_r$radius"

    override suspend fun transform(input: Bitmap, size: Size): Bitmap {
        val w = input.width.coerceAtLeast(1)
        val h = input.height.coerceAtLeast(1)
        val src = if (input.config == Bitmap.Config.ARGB_8888) {
            input.copy(Bitmap.Config.ARGB_8888, true)
        } else {
            input.copy(Bitmap.Config.ARGB_8888, true)
        }
        val pixels = IntArray(w * h)
        src.getPixels(pixels, 0, w, 0, 0, w, h)
        val tmp = IntArray(w * h)
        val r = radius.coerceIn(1, minOf(w, h) / 2).coerceAtLeast(1)

        repeat(2) {
            boxBlurHorizontal(pixels, tmp, w, h, r)
            boxBlurVertical(tmp, pixels, w, h, r)
        }
        src.setPixels(pixels, 0, w, 0, 0, w, h)
        return src
    }

    private fun boxBlurHorizontal(src: IntArray, dst: IntArray, w: Int, h: Int, r: Int) {
        val div = r * 2 + 1
        for (y in 0 until h) {
            val row = y * w
            var rSum = 0
            var gSum = 0
            var bSum = 0
            for (i in -r..r) {
                val p = src[row + i.coerceIn(0, w - 1)]
                rSum += (p ushr 16) and 0xFF
                gSum += (p ushr 8) and 0xFF
                bSum += p and 0xFF
            }
            for (x in 0 until w) {
                dst[row + x] = (0xFF shl 24) or ((rSum / div) shl 16) or ((gSum / div) shl 8) or (bSum / div)
                val pOut = src[row + (x - r).coerceIn(0, w - 1)]
                val pIn = src[row + (x + r + 1).coerceIn(0, w - 1)]
                rSum += ((pIn ushr 16) and 0xFF) - ((pOut ushr 16) and 0xFF)
                gSum += ((pIn ushr 8) and 0xFF) - ((pOut ushr 8) and 0xFF)
                bSum += (pIn and 0xFF) - (pOut and 0xFF)
            }
        }
    }

    private fun boxBlurVertical(src: IntArray, dst: IntArray, w: Int, h: Int, r: Int) {
        val div = r * 2 + 1
        for (x in 0 until w) {
            var rSum = 0
            var gSum = 0
            var bSum = 0
            for (i in -r..r) {
                val p = src[i.coerceIn(0, h - 1) * w + x]
                rSum += (p ushr 16) and 0xFF
                gSum += (p ushr 8) and 0xFF
                bSum += p and 0xFF
            }
            for (y in 0 until h) {
                dst[y * w + x] = (0xFF shl 24) or ((rSum / div) shl 16) or ((gSum / div) shl 8) or (bSum / div)
                val pOut = src[(y - r).coerceIn(0, h - 1) * w + x]
                val pIn = src[(y + r + 1).coerceIn(0, h - 1) * w + x]
                rSum += ((pIn ushr 16) and 0xFF) - ((pOut ushr 16) and 0xFF)
                gSum += ((pIn ushr 8) and 0xFF) - ((pOut ushr 8) and 0xFF)
                bSum += (pIn and 0xFF) - (pOut and 0xFF)
            }
        }
    }
}

private val SharedAuraBlur = FastAuraBlurTransformation(radius = 7)

fun buildAuraBackdropRequest(context: Context, url: String, blurEnabled: Boolean): ImageRequest {
    val builder = ImageRequest.Builder(context)
        .data(url)
        .crossfade(200)
    if (blurEnabled) {
        builder
            .size(160, 90)
            .scale(Scale.FILL)
            .allowHardware(false)
            .transformations(SharedAuraBlur)
    } else {
        builder
            .size(960, 540)
            .scale(Scale.FILL)
    }
    return builder.build()
}

fun buildPosterImageRequest(context: Context, url: String): ImageRequest =
    ImageRequest.Builder(context)
        .data(url)
        .size(520, 780)
        .scale(Scale.FILL)
        .crossfade(false)
        .build()

@Composable
fun BackdropAuraStage(
    backdropUrl: String,
    settings: AppSettings,
    modifier: Modifier = Modifier
) {
    val theme = settings.themePreset
    val context = LocalContext.current
    val request = remember(backdropUrl, settings.backdropBlurEnabled) {
        if (backdropUrl.isNotBlank()) {
            buildAuraBackdropRequest(context, backdropUrl, settings.backdropBlurEnabled)
        } else {
            null
        }
    }

    val radialBrush = remember(theme) {
        Brush.radialGradient(
            colors = listOf(
                theme.primaryColor.copy(alpha = 0.18f),
                theme.bgGlowColor.copy(alpha = 0.22f),
                Color.Transparent
            ),
            center = androidx.compose.ui.geometry.Offset(620f, 540f),
            radius = 980f
        )
    }
    val horizontalWash = remember(theme) {
        Brush.horizontalGradient(
            0.0f to theme.bgDeepColor.copy(alpha = 0.56f),
            0.38f to theme.bgDeepColor.copy(alpha = 0.44f),
            0.72f to theme.bgDeepColor.copy(alpha = 0.78f),
            1.0f to theme.bgDeepColor.copy(alpha = 0.92f)
        )
    }
    val verticalVignette = remember(theme) {
        Brush.verticalGradient(
            0.0f to theme.bgDeepColor.copy(alpha = 0.78f),
            0.18f to Color.Transparent,
            0.82f to Color.Transparent,
            1.0f to theme.bgDeepColor.copy(alpha = 0.90f)
        )
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(theme.bgDeepColor)
    ) {
        if (request != null) {
            AsyncImage(
                model = request,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        alpha = if (settings.backdropBlurEnabled) 0.52f else 0.24f
                    }
            )
        }

        // Single-node cinema ambient light pool + directional wash + vignette (zero nested Box overdraw)
        Box(
            modifier = Modifier
                .fillMaxSize()
                .drawBehind {
                    drawRect(brush = radialBrush)
                    drawRect(brush = horizontalWash)
                    drawRect(brush = verticalVignette)
                }
        )
    }
}

/**
 * Tactile 12dp architectural glass control button with crisp inverted focus state
 * and optional subtle remote keycap hint (e.g. "R", "U", "L", "OK") without template arrows.
 */
@Composable
fun GlassPillButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    keyHint: String? = null,
    selected: Boolean = false,
    accentColor: Color? = null,
    fontFamily: FontFamily? = null,
    fontSize: TextUnit = 13.5.sp,
    horizontalPadding: Dp = 16.dp,
    verticalPadding: Dp = 9.dp
) {
    val settings = LocalAppSettings.current
    val theme = settings.themePreset
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    val effectiveAccent = accentColor ?: theme.primaryColor

    val scaleState = animateFloatAsState(
        targetValue = if (isFocused) 1.045f else 1.0f,
        animationSpec = spring(stiffness = 920f, dampingRatio = 0.84f, visibilityThreshold = 0.001f),
        label = "btnScale"
    )

    val shape = RoundedCornerShape(12.dp)
    val contentColor = if (isFocused) SwayObsidianInk else SwayIvory

    val fillBrush = when {
        isFocused -> Brush.linearGradient(
            listOf(SwayIvory, SwayIvory.copy(alpha = 0.92f))
        )
        selected -> Brush.linearGradient(
            listOf(
                effectiveAccent.copy(alpha = 0.28f),
                effectiveAccent.copy(alpha = 0.16f)
            )
        )
        else -> Brush.linearGradient(
            listOf(
                Color.White.copy(alpha = 0.08f),
                Color.White.copy(alpha = 0.04f)
            )
        )
    }

    val borderStroke = when {
        isFocused -> BorderStroke(1.5.dp, SwayIvory)
        selected -> BorderStroke(1.2.dp, effectiveAccent.copy(alpha = 0.80f))
        else -> BorderStroke(1.dp, Color.White.copy(alpha = 0.13f))
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
        modifier = modifier
            .graphicsLayer {
                val s = scaleState.value
                scaleX = s
                scaleY = s
            }
            .shadow(
                elevation = if (isFocused) 10.dp else 0.dp,
                shape = shape,
                ambientColor = effectiveAccent,
                spotColor = effectiveAccent
            )
            .clip(shape)
            .background(fillBrush, shape)
            .border(borderStroke, shape)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            )
            .padding(horizontal = horizontalPadding, vertical = verticalPadding)
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (isFocused) SwayObsidianInk else if (selected) effectiveAccent else SwayIvory.copy(alpha = 0.85f),
                modifier = Modifier.size(16.dp)
            )
            Spacer(Modifier.width(7.dp))
        }
        Text(
            text = text,
            color = contentColor,
            fontFamily = fontFamily ?: LocalSwayFonts.current.body,
            fontSize = fontSize,
            fontWeight = if (isFocused || selected) FontWeight.Bold else FontWeight.SemiBold,
            letterSpacing = (-0.15).sp,
            maxLines = 1
        )
        if (keyHint != null) {
            Spacer(Modifier.width(8.dp))
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(5.dp))
                    .background(
                        if (isFocused) SwayObsidianInk.copy(alpha = 0.12f)
                        else Color.White.copy(alpha = 0.10f)
                    )
                    .padding(horizontal = 5.dp, vertical = 1.5.dp)
            ) {
                Text(
                    text = keyHint,
                    color = if (isFocused) SwayObsidianInk.copy(alpha = 0.8f) else SwayIvory.copy(alpha = 0.65f),
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}
