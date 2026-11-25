package com.burbsklopek.flickdeck.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.*
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.burbsklopek.flickdeck.data.remote.CatalogService
import com.burbsklopek.flickdeck.model.GlassIntensity
import com.burbsklopek.flickdeck.model.MediaItem
import com.burbsklopek.flickdeck.model.SwipeAction
import com.burbsklopek.flickdeck.ui.components.TrailerLauncher
import com.burbsklopek.flickdeck.ui.theme.*
import kotlinx.coroutines.launch

@Composable
fun CardDetailModal(
    item: MediaItem,
    currentAction: SwipeAction? = null,
    currentRating: Int?,
    onDismiss: () -> Unit,
    onMarkWatched: () -> Unit,
    onMarkToWatch: () -> Unit,
    onPass: () -> Unit,
    onRate: (Int) -> Unit
) {
    val settings = LocalAppSettings.current
    val theme = settings.themePreset
    val swayFonts = LocalSwayFonts.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val trailerResolver = remember { CatalogService() }
    var isResolvingTrailer by remember(item.id) { mutableStateOf(false) }
    val initialFocus = remember { FocusRequester() }

    BackHandler(onBack = onDismiss)

    LaunchedEffect(item.id) {
        runCatching { initialFocus.requestFocus() }
    }

    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.80f))
            .modalScrimDismiss(onDismiss)
            .padding(vertical = 22.dp, horizontal = 40.dp)
    ) {
        Column(
            modifier = Modifier
                .width(820.dp)
                .heightIn(max = 486.dp)
                .liquidGlass(
                    theme = theme,
                    intensity = GlassIntensity.ULTRA_GLASS,
                    shape = RoundedCornerShape(26.dp),
                    isFocused = true
                )
                .consumeModalTouches()
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(206.dp)
                    .clip(RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp))
            ) {
                val heroArt = item.backdropUrl.ifBlank { item.posterUrl }
                if (heroArt.isNotBlank()) {
                    AsyncImage(
                        model = heroArt,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        alignment = Alignment.TopCenter,
                        modifier = Modifier.fillMaxSize()
                    )
                }
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                0.0f to Color.Black.copy(alpha = 0.08f),
                                0.45f to Color.Black.copy(alpha = 0.34f),
                                1.0f to theme.cardTintColor.copy(alpha = 0.98f)
                            )
                        )
                )

                Row(
                    verticalAlignment = Alignment.Bottom,
                    horizontalArrangement = Arrangement.spacedBy(18.dp),
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .fillMaxWidth()
                        .padding(horizontal = 28.dp, vertical = 14.dp)
                ) {
                    if (item.posterUrl.isNotBlank()) {
                        AsyncImage(
                            model = item.posterUrl,
                            contentDescription = item.title,
                            contentScale = ContentScale.Crop,
                            alignment = Alignment.TopCenter,
                            modifier = Modifier
                                .width(80.dp)
                                .height(118.dp)
                                .shadow(14.dp, RoundedCornerShape(12.dp))
                                .clip(RoundedCornerShape(12.dp))
                                .border(1.2.dp, Color.White.copy(alpha = 0.30f), RoundedCornerShape(12.dp))
                        )
                    }

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = item.title,
                            color = SwayIvory,
                            fontFamily = swayFonts.display,
                            fontSize = 32.sp,
                            lineHeight = 36.sp,
                            fontWeight = FontWeight.ExtraBold,
                            letterSpacing = swayFonts.titleTrackingSp.sp,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(Modifier.height(5.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                            if (item.imdbRating.isNotBlank()) {
                                Text(
                                    text = "IMDb ${item.imdbRating}",
                                    color = theme.secondaryColor,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                            if (item.year.isNotBlank()) {
                                Text(text = item.year, color = SwaySilver, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                            }
                            val dur = item.seasonsCount?.let { if (it == 1) "1 season" else "$it seasons" } ?: item.runtime
                            if (dur.isNotBlank()) {
                                Text(text = dur, color = SwaySilver, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                            }
                            if (item.genres.isNotEmpty()) {
                                Text(
                                    text = item.genres.take(3).joinToString(", "),
                                    color = theme.primaryColor,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
            }

            Column(
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.padding(horizontal = 28.dp, vertical = 16.dp)
            ) {
                Text(
                    text = item.overview.ifBlank { "No extended synopsis available for this title." },
                    color = SwayIvory.copy(alpha = 0.88f),
                    fontSize = 14.5.sp,
                    lineHeight = 21.sp,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )

                if (!item.awards.isNullOrBlank()) {
                    Text(
                        text = item.awards,
                        color = theme.secondaryColor.copy(alpha = 0.90f),
                        fontSize = 12.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                if (item.cast.isNotEmpty() || item.creators.isNotEmpty()) {
                    val credits = buildList {
                        if (item.cast.isNotEmpty()) add("With ${item.cast.joinToString(", ")}")
                        if (item.creators.isNotEmpty()) add("By ${item.creators.joinToString(", ")}")
                    }.joinToString("    ")
                    Text(
                        text = credits,
                        color = SwaySilver,
                        fontSize = 12.5.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "Rate",
                        color = SwaySilver,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(Modifier.width(4.dp))
                    (6..10).forEach { score ->
                        GlassPillButton(
                            text = "$score",
                            selected = currentRating == score,
                            accentColor = theme.secondaryColor,
                            fontSize = 12.5.sp,
                            horizontalPadding = 12.dp,
                            verticalPadding = 6.dp,
                            onClick = { onRate(score) }
                        )
                    }
                }

                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(Color.White.copy(alpha = 0.08f))
                )

                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    GlassPillButton(
                        text = if (isResolvingTrailer) "Finding trailer..." else "Play ${settings.defaultVideoQuality.label} trailer",
                        icon = Icons.Rounded.PlayArrow,
                        selected = true,
                        accentColor = theme.primaryColor,
                        onClick = {
                            if (isResolvingTrailer) return@GlassPillButton
                            val directId = item.trailerYoutubeId
                            if (!directId.isNullOrBlank()) {
                                TrailerLauncher.launchTrailer(
                                    context = context,
                                    item = item,
                                    quality = settings.defaultVideoQuality,
                                    resolvedYoutubeId = directId
                                )
                            } else {
                                isResolvingTrailer = true
                                scope.launch {
                                    val resolved = trailerResolver.resolveTrailerYoutubeId(item)
                                    isResolvingTrailer = false
                                    TrailerLauncher.launchTrailer(
                                        context = context,
                                        item = item,
                                        quality = settings.defaultVideoQuality,
                                        resolvedYoutubeId = resolved
                                    )
                                }
                            }
                        },
                        modifier = Modifier.focusRequester(initialFocus)
                    )

                    GlassPillButton(
                        text = if (currentAction == SwipeAction.TO_WATCH) "In Watchlist" else "Watchlist",
                        selected = currentAction == SwipeAction.TO_WATCH,
                        accentColor = theme.toWatchColor,
                        onClick = onMarkToWatch
                    )

                    GlassPillButton(
                        text = "Watched",
                        selected = currentAction == SwipeAction.WATCHED,
                        accentColor = theme.watchedColor,
                        onClick = onMarkWatched
                    )

                    GlassPillButton(
                        text = "Pass",
                        selected = currentAction == SwipeAction.SKIPPED,
                        accentColor = theme.passColor,
                        onClick = onPass
                    )

                    Spacer(Modifier.weight(1f))

                    GlassPillButton(
                        text = "Close",
                        onClick = onDismiss
                    )
                }
            }
        }
    }
}
