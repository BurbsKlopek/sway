package com.burbsklopek.flickdeck.ui.screens

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.*
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.burbsklopek.flickdeck.model.MediaItem
import com.burbsklopek.flickdeck.model.SwipeAction
import com.burbsklopek.flickdeck.model.TrackedEntry
import com.burbsklopek.flickdeck.ui.theme.*
import com.burbsklopek.flickdeck.viewmodel.FlickDeckUiState

@Composable
fun LibraryScreen(
    uiState: FlickDeckUiState,
    onMoveEntry: (TrackedEntry, SwipeAction) -> Unit,
    onRemoveEntry: (TrackedEntry) -> Unit,
    onOpenDetails: (MediaItem) -> Unit
) {
    val settings = LocalAppSettings.current
    val theme = settings.themePreset
    var selectedShelf by remember { mutableStateOf(SwipeAction.TO_WATCH) }

    val itemsToDisplay = when (selectedShelf) {
        SwipeAction.TO_WATCH -> uiState.toWatchEntries
        SwipeAction.WATCHED -> uiState.watchedEntries
        SwipeAction.SKIPPED -> uiState.skippedEntries
    }

    var lastModalItemId by remember { mutableStateOf<String?>(null) }
    var restoreFocusItemId by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(uiState.detailModalItem?.id) {
        val activeModalId = uiState.detailModalItem?.id
        if (activeModalId != null) {
            lastModalItemId = activeModalId
        } else if (lastModalItemId != null) {
            val targetId = lastModalItemId
            lastModalItemId = null
            restoreFocusItemId = if (itemsToDisplay.any { it.item.id == targetId }) {
                targetId
            } else {
                itemsToDisplay.firstOrNull()?.item?.id
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 48.dp)
        ) {
            GlassPillButton(
                text = "Watchlist  ${uiState.toWatchEntries.size}",
                selected = selectedShelf == SwipeAction.TO_WATCH,
                accentColor = theme.toWatchColor,
                onClick = { selectedShelf = SwipeAction.TO_WATCH }
            )
            GlassPillButton(
                text = "Watched  ${uiState.watchedEntries.size}",
                selected = selectedShelf == SwipeAction.WATCHED,
                accentColor = theme.watchedColor,
                onClick = { selectedShelf = SwipeAction.WATCHED }
            )
            GlassPillButton(
                text = "Passed  ${uiState.skippedEntries.size}",
                selected = selectedShelf == SwipeAction.SKIPPED,
                accentColor = theme.passColor,
                onClick = { selectedShelf = SwipeAction.SKIPPED }
            )

            Spacer(Modifier.weight(1f))

            Text(
                text = "Select a poster for details, Menu to switch shelf, Backspace to remove",
                color = SwaySilver,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium
            )
        }

        Spacer(Modifier.height(6.dp))

        if (itemsToDisplay.isEmpty()) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 48.dp, vertical = 22.dp)
            ) {
                Column(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier
                        .width(480.dp)
                        .liquidGlass(theme, settings.glassIntensity, RoundedCornerShape(24.dp))
                        .padding(32.dp)
                ) {
                    Text(
                        text = when (selectedShelf) {
                            SwipeAction.TO_WATCH -> "Your Watchlist is empty"
                            SwipeAction.WATCHED -> "Nothing marked watched yet"
                            SwipeAction.SKIPPED -> "No passed titles"
                        },
                        color = SwayIvory,
                        fontFamily = LocalSwayFonts.current.display,
                        fontSize = 24.sp,
                        fontWeight = FontWeight.ExtraBold
                    )
                    Text(
                        text = "Swipe right on the Deck to save shows for later, or connect Trakt or Simkl in Sync to import your library.",
                        color = SwaySilver,
                        fontSize = 14.sp,
                        lineHeight = 20.sp
                    )
                }
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(5),
                horizontalArrangement = Arrangement.spacedBy(18.dp),
                verticalArrangement = Arrangement.spacedBy(22.dp),
                contentPadding = PaddingValues(start = 48.dp, end = 48.dp, top = 16.dp, bottom = 36.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                items(itemsToDisplay, key = { it.item.id }) { entry ->
                    PosterShelfItem(
                        entry = entry,
                        restoreFocus = restoreFocusItemId == entry.item.id,
                        onFocusRestored = {
                            if (restoreFocusItemId == entry.item.id) {
                                restoreFocusItemId = null
                            }
                        },
                        onOpenDetails = { onOpenDetails(entry.item) },
                        onToggleStatus = {
                            val next = if (entry.action == SwipeAction.WATCHED) SwipeAction.TO_WATCH else SwipeAction.WATCHED
                            onMoveEntry(entry, next)
                        },
                        onRemove = { onRemoveEntry(entry) }
                    )
                }
            }
        }
    }
}

@Composable
private fun PosterShelfItem(
    entry: TrackedEntry,
    restoreFocus: Boolean,
    onFocusRestored: () -> Unit,
    onOpenDetails: () -> Unit,
    onToggleStatus: () -> Unit,
    onRemove: () -> Unit
) {
    val theme = LocalAppSettings.current.themePreset
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    val itemFocusRequester = remember { FocusRequester() }

    LaunchedEffect(restoreFocus) {
        if (restoreFocus) {
            runCatching { itemFocusRequester.requestFocus() }
            onFocusRestored()
        }
    }

    val scaleState = animateFloatAsState(
        targetValue = if (isFocused) 1.05f else 1.0f,
        animationSpec = spring(stiffness = 900f, dampingRatio = 0.84f, visibilityThreshold = 0.001f),
        label = "shelfPosterScale"
    )

    Column(
        modifier = Modifier
            .focusRequester(itemFocusRequester)
            .graphicsLayer {
                val s = scaleState.value
                scaleX = s
                scaleY = s
            }
            .onPreviewKeyEvent { ev ->
                if (ev.type != KeyEventType.KeyDown || ev.nativeKeyEvent.repeatCount > 0) {
                    return@onPreviewKeyEvent false
                }
                when (ev.key) {
                    Key.Menu, Key.Bookmark -> {
                        onToggleStatus()
                        true
                    }
                    Key.Delete, Key.Backspace -> {
                        onRemove()
                        true
                    }
                    else -> false
                }
            }
            .clickable(interactionSource = interactionSource, indication = null, onClick = onOpenDetails)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(0.675f)
                .shadow(
                    elevation = if (isFocused) 22.dp else 10.dp,
                    shape = RoundedCornerShape(16.dp),
                    ambientColor = if (isFocused) theme.primaryColor else Color.Black,
                    spotColor = if (isFocused) theme.primaryColor else Color.Black
                )
                .clip(RoundedCornerShape(16.dp))
                .background(theme.cardTintColor)
                .border(
                    width = if (isFocused) 2.dp else 1.dp,
                    color = if (isFocused) SwayIvory else Color.White.copy(alpha = 0.14f),
                    shape = RoundedCornerShape(16.dp)
                )
        ) {
            if (entry.item.posterUrl.isNotBlank()) {
                val posterContext = LocalContext.current
                val posterRequest = remember(entry.item.posterUrl) {
                    buildPosterImageRequest(posterContext, entry.item.posterUrl)
                }
                AsyncImage(
                    model = posterRequest,
                    contentDescription = entry.item.title,
                    contentScale = ContentScale.Crop,
                    alignment = Alignment.TopCenter,
                    modifier = Modifier.fillMaxSize()
                )
            }

            if (entry.syncedTrakt || entry.syncedSimkl || entry.userRating != null) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(8.dp)
                ) {
                    if (entry.syncedTrakt) SyncTag("Trakt", Color(0xFFEF4444))
                    if (entry.syncedSimkl) SyncTag("Simkl", Color(0xFF38BDF8))
                    if (entry.userRating != null) SyncTag("${entry.userRating}/10", theme.secondaryColor)
                }
            }
        }

        Spacer(Modifier.height(8.dp))

        Text(
            text = entry.item.title,
            color = if (isFocused) SwayIvory else SwayIvory.copy(alpha = 0.90f),
            fontSize = 13.5.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )

        Text(
            text = listOfNotNull(
                entry.item.year.takeIf { it.isNotBlank() },
                entry.item.imdbRating.takeIf { it.isNotBlank() }?.let { "IMDb $it" }
            ).joinToString("   "),
            color = SwaySilver,
            fontSize = 12.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun SyncTag(label: String, color: Color) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(SwayObsidianInk.copy(alpha = 0.85f))
            .border(1.dp, color.copy(alpha = 0.7f), RoundedCornerShape(6.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp)
    ) {
        Text(
            text = label,
            color = color,
            fontSize = 9.5.sp,
            fontWeight = FontWeight.Bold
        )
    }
}
