package com.burbsklopek.flickdeck.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.ImageLoader
import coil.compose.AsyncImage
import coil.imageLoader
import coil.request.ImageRequest
import com.burbsklopek.flickdeck.model.*
import com.burbsklopek.flickdeck.ui.theme.*
import com.burbsklopek.flickdeck.viewmodel.FlickDeckUiState
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.hypot

@Composable
fun SwipeDeckScreen(
    uiState: FlickDeckUiState,
    genres: List<String>,
    showFilterSheet: Boolean,
    onToggleFilterSheet: (Boolean) -> Unit,
    topNavFocusRequester: FocusRequester,
    onSelectMediaType: (MediaType) -> Unit,
    onSelectCategory: (FeedCategory) -> Unit,
    onSelectGenre: (String) -> Unit,
    onSearchQueryChange: (String) -> Unit,
    onSwipe: (MediaItem, SwipeAction) -> Unit,
    onUndo: () -> Unit,
    onClearRewind: () -> Unit,
    onOpenDetails: (MediaItem) -> Unit,
    onResetPassed: () -> Unit,
    onRefreshDeck: () -> Unit
) {
    val settings = LocalAppSettings.current
    val theme = settings.themePreset
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val stageFocusRequester = remember { FocusRequester() }
    val dockButtonFocusRequester = remember { FocusRequester() }

    // Preload upcoming 5 posters & pre-blurred 160x90 aura backdrops into RAM cache for 0ms transitions
    val upcomingIds = remember(uiState.deckCards) { uiState.deckCards.take(5) }
    LaunchedEffect(upcomingIds, settings.backdropBlurEnabled) {
        val loader: ImageLoader = context.imageLoader
        upcomingIds.forEach { card ->
            if (card.posterUrl.isNotBlank()) {
                loader.enqueue(buildPosterImageRequest(context, card.posterUrl))
            }
            if (card.backdropUrl.isNotBlank()) {
                loader.enqueue(buildAuraBackdropRequest(context, card.backdropUrl, settings.backdropBlurEnabled))
            }
        }
    }

    // Focus the Swipe Stage (or Watchlist button when in BUTTON_DOCK mode) automatically
    LaunchedEffect(uiState.deckCards.isNotEmpty(), showFilterSheet, settings.controlScheme) {
        if (uiState.deckCards.isNotEmpty() && !showFilterSheet) {
            if (settings.controlScheme == SwipeControlScheme.BUTTON_DOCK) {
                runCatching { dockButtonFocusRequester.requestFocus() }
            } else {
                runCatching { stageFocusRequester.requestFocus() }
            }
        }
    }

    val topCard = uiState.deckCards.firstOrNull()

    val offsetX = remember(topCard?.id) { Animatable(0f) }
    val offsetY = remember(topCard?.id) { Animatable(0f) }
    var activeAutoStamp by remember(topCard?.id) { mutableStateOf<SwipeAction?>(null) }
    var isAnimatingOut by remember(topCard?.id) { mutableStateOf(false) }

    // Rewind spring entrance when Undo is triggered
    LaunchedEffect(uiState.rewindTriggerItem?.id) {
        val rewound = uiState.rewindTriggerItem
        if (rewound != null && rewound.id == topCard?.id) {
            offsetX.snapTo(-680f)
            offsetY.snapTo(-24f)
            coroutineScope {
                launch {
                    offsetX.animateTo(
                        0f,
                        spring(
                            stiffness = settings.swipeSpeed.stiffness,
                            dampingRatio = settings.swipeSpeed.damping,
                            visibilityThreshold = 1f
                        )
                    )
                }
                launch {
                    offsetY.animateTo(
                        0f,
                        spring(
                            stiffness = settings.swipeSpeed.stiffness,
                            dampingRatio = settings.swipeSpeed.damping,
                            visibilityThreshold = 1f
                        )
                    )
                }
            }
            onClearRewind()
        }
    }

    fun triggerAnimatedSwipe(card: MediaItem, action: SwipeAction) {
        if (isAnimatingOut) return
        isAnimatingOut = true
        activeAutoStamp = action

        val (targetX, targetY) = when (action) {
            SwipeAction.TO_WATCH -> 1180f to -30f
            SwipeAction.WATCHED -> {
                if (settings.controlScheme == SwipeControlScheme.BINARY_SPLIT) 1180f to -30f
                else 0f to -860f
            }
            SwipeAction.SKIPPED -> -1180f to 30f
        }

        scope.launch {
            coroutineScope {
                launch {
                    offsetX.animateTo(
                        targetX,
                        spring(
                            stiffness = settings.swipeSpeed.stiffness,
                            dampingRatio = settings.swipeSpeed.damping,
                            visibilityThreshold = 4f
                        )
                    )
                }
                launch {
                    offsetY.animateTo(
                        targetY,
                        spring(
                            stiffness = settings.swipeSpeed.stiffness,
                            dampingRatio = settings.swipeSpeed.damping,
                            visibilityThreshold = 4f
                        )
                    )
                }
            }
            onSwipe(card, action)
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        when {
            (uiState.isLoadingDeck || uiState.isFetchingMore) && uiState.deckCards.isEmpty() -> {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.align(Alignment.Center)
                ) {
                    CircularProgressIndicator(
                        color = theme.primaryColor,
                        strokeWidth = 3.dp,
                        modifier = Modifier.size(44.dp)
                    )
                    Spacer(Modifier.height(14.dp))
                    Text(
                        text = "Preparing your screening deck",
                        color = SwaySilver,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }

            uiState.deckCards.isEmpty() -> {
                EmptyDeckFolio(
                    searchQuery = uiState.searchQuery,
                    hasSkipped = uiState.skippedEntries.isNotEmpty(),
                    canUndo = uiState.undoStack.isNotEmpty(),
                    onUndo = onUndo,
                    onResetPassed = onResetPassed,
                    onRefreshDeck = {
                        if (uiState.searchQuery.isNotBlank()) {
                            onSearchQueryChange("")
                        } else {
                            onRefreshDeck()
                        }
                    },
                    onOpenFilters = { onToggleFilterSheet(true) },
                    modifier = Modifier.align(Alignment.Center)
                )
            }

            topCard != null -> {
                val stageInteraction = remember { MutableInteractionSource() }
                val isStageFocused by stageInteraction.collectIsFocusedAsState()
                var centerPressedOnStage by remember(topCard.id) { mutableStateOf(false) }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(30.dp),
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(start = 48.dp, end = 42.dp, top = 14.dp, bottom = 22.dp)
                        .then(
                            if (settings.controlScheme != SwipeControlScheme.BUTTON_DOCK) {
                                Modifier
                                    .focusRequester(stageFocusRequester)
                                    .onPreviewKeyEvent { event ->
                                        val isCenterKey = event.key == Key.DirectionCenter ||
                                            event.key == Key.Enter ||
                                            event.key == Key.NumPadEnter
                                        if (isCenterKey) {
                                            when (event.type) {
                                                KeyEventType.KeyDown -> {
                                                    if (event.nativeKeyEvent.repeatCount == 0) {
                                                        centerPressedOnStage = true
                                                    }
                                                    return@onPreviewKeyEvent true
                                                }
                                                KeyEventType.KeyUp -> {
                                                    if (centerPressedOnStage) {
                                                        centerPressedOnStage = false
                                                        onOpenDetails(topCard)
                                                    }
                                                    return@onPreviewKeyEvent true
                                                }
                                            }
                                        }
                                        if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                                        if (event.nativeKeyEvent.repeatCount > 0) return@onPreviewKeyEvent true
                                        when (settings.controlScheme) {
                                            SwipeControlScheme.TINDER_3WAY -> when (event.key) {
                                                Key.DirectionRight -> {
                                                    triggerAnimatedSwipe(topCard, SwipeAction.TO_WATCH)
                                                    true
                                                }
                                                Key.DirectionUp -> {
                                                    triggerAnimatedSwipe(topCard, SwipeAction.WATCHED)
                                                    true
                                                }
                                                Key.DirectionLeft -> {
                                                    triggerAnimatedSwipe(topCard, SwipeAction.SKIPPED)
                                                    true
                                                }
                                                Key.DirectionDown, Key.MediaRewind -> {
                                                    onUndo()
                                                    true
                                                }
                                                Key.Menu -> {
                                                    onToggleFilterSheet(true)
                                                    true
                                                }
                                                Key.Back -> {
                                                    runCatching { topNavFocusRequester.requestFocus() }
                                                    true
                                                }
                                                else -> false
                                            }
                                            SwipeControlScheme.BINARY_SPLIT -> when (event.key) {
                                                Key.DirectionRight -> {
                                                    triggerAnimatedSwipe(topCard, SwipeAction.WATCHED)
                                                    true
                                                }
                                                Key.DirectionLeft -> {
                                                    triggerAnimatedSwipe(topCard, SwipeAction.TO_WATCH)
                                                    true
                                                }
                                                Key.DirectionDown -> {
                                                    triggerAnimatedSwipe(topCard, SwipeAction.SKIPPED)
                                                    true
                                                }
                                                Key.DirectionUp, Key.Back -> {
                                                    runCatching { topNavFocusRequester.requestFocus() }
                                                    true
                                                }
                                                Key.MediaRewind -> {
                                                    onUndo()
                                                    true
                                                }
                                                else -> false
                                            }
                                            SwipeControlScheme.BUTTON_DOCK -> false
                                        }
                                    }
                                    .focusable(interactionSource = stageInteraction)
                            } else {
                                Modifier
                            }
                        )
                ) {
                    // ── Left Hero Element: Tactile Fanned 3-Poster Physical Screening Deck ──
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .width(292.dp)
                            .fillMaxHeight()
                    ) {
                        val visibleStack = uiState.deckCards.take(3)
                        for (idx in visibleStack.indices.reversed()) {
                            val card = visibleStack[idx]
                            key(card.id) {
                                val isFront = idx == 0

                                PhysicalPosterCard(
                                    item = card,
                                    stackIndex = idx,
                                    isFront = isFront,
                                    isFocused = isFront && isStageFocused,
                                    offsetXProvider = { offsetX.value },
                                    offsetYProvider = { offsetY.value },
                                    activeAutoStamp = if (isFront) activeAutoStamp else null,
                                    onDragDelta = { dx, dy ->
                                        if (isFront && !isAnimatingOut) {
                                            scope.launch {
                                                offsetX.snapTo(offsetX.value + dx)
                                                offsetY.snapTo(offsetY.value + dy)
                                            }
                                        }
                                    },
                                    onDragEnd = {
                                        if (isFront && !isAnimatingOut) {
                                            val curX = offsetX.value
                                            val curY = offsetY.value
                                            when {
                                                curX > 180f -> triggerAnimatedSwipe(
                                                    card,
                                                    if (settings.controlScheme == SwipeControlScheme.BINARY_SPLIT)
                                                        SwipeAction.WATCHED else SwipeAction.TO_WATCH
                                                )
                                                curX < -180f -> triggerAnimatedSwipe(
                                                    card,
                                                    if (settings.controlScheme == SwipeControlScheme.BINARY_SPLIT)
                                                        SwipeAction.TO_WATCH else SwipeAction.SKIPPED
                                                )
                                                curY < -160f -> triggerAnimatedSwipe(card, SwipeAction.WATCHED)
                                                else -> {
                                                    scope.launch {
                                                        launch { offsetX.animateTo(0f, spring(stiffness = 780f, dampingRatio = 0.84f, visibilityThreshold = 1f)) }
                                                        launch { offsetY.animateTo(0f, spring(stiffness = 780f, dampingRatio = 0.84f, visibilityThreshold = 1f)) }
                                                    }
                                                }
                                            }
                                        }
                                    },
                                    onClick = {
                                        if (isFront) {
                                            if (settings.controlScheme != SwipeControlScheme.BUTTON_DOCK && !isStageFocused) {
                                                runCatching { stageFocusRequester.requestFocus() }
                                            } else {
                                                onOpenDetails(card)
                                            }
                                        }
                                    }
                                )
                            }
                        }
                    }

                    // ── Right Editorial Smoked-Glass Folio ──
                    EditorialFolioPanel(
                        item = topCard,
                        canUndo = uiState.undoStack.isNotEmpty(),
                        controlScheme = settings.controlScheme,
                        dockButtonFocusRequester = dockButtonFocusRequester,
                        onPass = { triggerAnimatedSwipe(topCard, SwipeAction.SKIPPED) },
                        onToWatch = { triggerAnimatedSwipe(topCard, SwipeAction.TO_WATCH) },
                        onWatched = { triggerAnimatedSwipe(topCard, SwipeAction.WATCHED) },
                        onDetails = { onOpenDetails(topCard) },
                        onUndo = onUndo,
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                    )
                }
            }
        }

        // ── Summonable Frosted Filter & Search Sheet (zero clutter when closed!) ──
        AnimatedVisibility(
            visible = showFilterSheet,
            enter = fadeIn(tween(150)) + scaleIn(initialScale = 0.96f, animationSpec = tween(150)),
            exit = fadeOut(tween(120)) + scaleOut(targetScale = 0.96f, animationSpec = tween(120))
        ) {
            FilterDrawerModal(
                uiState = uiState,
                genres = genres,
                onSelectMediaType = onSelectMediaType,
                onSelectCategory = onSelectCategory,
                onSelectGenre = onSelectGenre,
                onSearchQueryChange = onSearchQueryChange,
                onDismiss = { onToggleFilterSheet(false) }
            )
        }
    }
}

@Composable
private fun PhysicalPosterCard(
    item: MediaItem,
    stackIndex: Int,
    isFront: Boolean,
    isFocused: Boolean,
    offsetXProvider: () -> Float,
    offsetYProvider: () -> Float,
    activeAutoStamp: SwipeAction?,
    onDragDelta: (Float, Float) -> Unit,
    onDragEnd: () -> Unit,
    onClick: () -> Unit
) {
    val settings = LocalAppSettings.current
    val theme = settings.themePreset
    val posterShape = RoundedCornerShape(20.dp)

    val rimBrush = if (isFocused) {
        Brush.linearGradient(
            listOf(
                SwayIvory.copy(alpha = 0.95f),
                theme.primaryColor.copy(alpha = 0.80f),
                theme.secondaryColor.copy(alpha = 0.65f)
            )
        )
    } else {
        Brush.linearGradient(
            listOf(
                Color.White.copy(alpha = 0.34f),
                Color.White.copy(alpha = 0.08f)
            )
        )
    }

    Box(
        modifier = Modifier
            .width(254.dp)
            .height(380.dp)
            .graphicsLayer {
                val ox = offsetXProvider()
                val oy = offsetYProvider()
                if (isFront) {
                    translationX = ox
                    translationY = oy
                    rotationZ = (ox / 32f).coerceIn(-18f, 18f)
                    scaleX = 1f
                    scaleY = 1f
                    alpha = 1f
                } else {
                    val progress = (hypot(ox, oy) / 380f).coerceIn(0f, 1f)
                    if (stackIndex == 1) {
                        translationX = 22f * (1f - progress)
                        translationY = 12f * (1f - progress)
                        rotationZ = 4.8f * (1f - progress)
                        val s = 0.94f + (0.06f * progress)
                        scaleX = s
                        scaleY = s
                        alpha = 0.82f + (0.18f * progress)
                    } else {
                        translationX = -24f * (1f - progress * 0.5f)
                        translationY = 20f * (1f - progress * 0.5f)
                        rotationZ = -6.2f * (1f - progress * 0.5f)
                        val s = 0.89f + (0.05f * progress)
                        scaleX = s
                        scaleY = s
                        alpha = 0.58f + (0.24f * progress)
                    }
                }
            }
            .then(
                if (isFront && settings.cardShadowGlow) {
                    Modifier.shadow(
                        elevation = 16.dp,
                        shape = posterShape,
                        ambientColor = if (isFocused) theme.primaryColor else Color.Black,
                        spotColor = if (isFocused) theme.primaryColor else Color.Black
                    )
                } else {
                    Modifier
                }
            )
            .clip(posterShape)
            .background(theme.cardTintColor)
            .border(
                width = if (isFocused) 2.dp else 1.2.dp,
                brush = rimBrush,
                shape = posterShape
            )
            .then(
                if (isFront) {
                    Modifier
                        .pointerInput(item.id) {
                            detectDragGestures(
                                onDragEnd = { onDragEnd() },
                                onDragCancel = { onDragEnd() },
                                onDrag = { change, dragAmount ->
                                    change.consume()
                                    onDragDelta(dragAmount.x, dragAmount.y)
                                }
                            )
                        }
                        .clickable(onClick = onClick)
                } else {
                    Modifier
                }
            )
    ) {
        if (item.posterUrl.isNotBlank()) {
            val posterContext = LocalContext.current
            val posterRequest = remember(item.posterUrl) {
                buildPosterImageRequest(posterContext, item.posterUrl)
            }
            AsyncImage(
                model = posterRequest,
                contentDescription = item.title,
                contentScale = ContentScale.Crop,
                alignment = Alignment.TopCenter,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Box(Modifier.fillMaxSize().padding(20.dp), contentAlignment = Alignment.BottomStart) {
                Text(
                    text = item.title,
                    color = SwayIvory,
                    fontFamily = LocalSwayFonts.current.display,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.ExtraBold
                )
            }
        }

        // Subtle dim on background fanned cards so the front poster pops unmistakably
        if (!isFront) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.28f))
            )
        }

        // Frosted Glass Verdict Stamps driven purely in GPU layer phase (zero recomposition at 120fps)
        if (isFront) {
            val isBinary = settings.controlScheme == SwipeControlScheme.BINARY_SPLIT
            GlassVerdictStamp(
                label = if (isBinary) "Watched" else "Watchlist",
                color = if (isBinary) theme.watchedColor else theme.toWatchColor,
                alphaProvider = {
                    val ox = offsetXProvider()
                    when {
                        activeAutoStamp == SwipeAction.TO_WATCH -> 1f
                        activeAutoStamp == SwipeAction.WATCHED && isBinary -> 1f
                        ox > 30f -> ((ox - 30f) / 150f).coerceIn(0f, 1f)
                        else -> 0f
                    }
                },
                rotation = -10f,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(18.dp)
            )
            GlassVerdictStamp(
                label = if (isBinary) "Watchlist" else "Pass",
                color = if (isBinary) theme.toWatchColor else theme.passColor,
                alphaProvider = {
                    val ox = offsetXProvider()
                    when {
                        activeAutoStamp == SwipeAction.SKIPPED -> 1f
                        activeAutoStamp == SwipeAction.TO_WATCH && isBinary -> 1f
                        ox < -30f -> ((-ox - 30f) / 150f).coerceIn(0f, 1f)
                        else -> 0f
                    }
                },
                rotation = 10f,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(18.dp)
            )
            GlassVerdictStamp(
                label = "Watched",
                color = theme.watchedColor,
                alphaProvider = {
                    val ox = offsetXProvider()
                    val oy = offsetYProvider()
                    when {
                        activeAutoStamp == SwipeAction.WATCHED && !isBinary -> 1f
                        oy < -30f && abs(oy) > abs(ox) -> ((-oy - 30f) / 130f).coerceIn(0f, 1f)
                        else -> 0f
                    }
                },
                rotation = 0f,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 24.dp)
            )
        }
    }
}

@Composable
private fun GlassVerdictStamp(
    label: String,
    color: Color,
    alphaProvider: () -> Float,
    rotation: Float,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .graphicsLayer {
                val a = alphaProvider()
                this.alpha = a
                rotationZ = rotation
                val s = 0.9f + (0.1f * a)
                scaleX = s
                scaleY = s
            }
            .shadow(18.dp, RoundedCornerShape(12.dp), ambientColor = color, spotColor = color)
            .clip(RoundedCornerShape(12.dp))
            .background(SwayObsidianInk.copy(alpha = 0.88f))
            .border(2.dp, color, RoundedCornerShape(12.dp))
            .padding(horizontal = 18.dp, vertical = 8.dp)
    ) {
        Text(
            text = label,
            color = color,
            fontFamily = LocalSwayFonts.current.display,
            fontSize = 22.sp,
            fontWeight = FontWeight.ExtraBold,
            letterSpacing = (-0.4).sp
        )
    }
}

@Composable
private fun EditorialFolioPanel(
    item: MediaItem,
    canUndo: Boolean,
    controlScheme: SwipeControlScheme,
    dockButtonFocusRequester: FocusRequester,
    onPass: () -> Unit,
    onToWatch: () -> Unit,
    onWatched: () -> Unit,
    onDetails: () -> Unit,
    onUndo: () -> Unit,
    modifier: Modifier = Modifier
) {
    val settings = LocalAppSettings.current
    val theme = settings.themePreset
    val swayFonts = LocalSwayFonts.current

    Column(
        verticalArrangement = Arrangement.SpaceBetween,
        modifier = modifier
            .padding(vertical = 10.dp)
            .liquidGlass(
                theme = theme,
                intensity = settings.glassIntensity,
                shape = RoundedCornerShape(26.dp),
                enableShadow = settings.cardShadowGlow
            )
            .padding(horizontal = 26.dp, vertical = 26.dp)
    ) {
        AnimatedContent(
            targetState = item,
            transitionSpec = {
                fadeIn(tween(130, easing = FastOutSlowInEasing)) togetherWith
                    fadeOut(tween(75, easing = FastOutSlowInEasing))
            },
            contentKey = { it.id },
            label = "editorialFolioTransition",
            modifier = Modifier.weight(1f)
        ) { current ->
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                // 1. Quiet Structured Metadata Row (no middle-dot soup, clean tabular spacing)
                Row(
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (current.imdbRating.isNotBlank()) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(7.dp))
                                .background(theme.secondaryColor.copy(alpha = 0.16f))
                                .border(1.dp, theme.secondaryColor.copy(alpha = 0.45f), RoundedCornerShape(7.dp))
                                .padding(horizontal = 8.dp, vertical = 3.dp)
                        ) {
                            Text(
                                text = "IMDb ${current.imdbRating}",
                                color = theme.secondaryColor,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                    if (current.year.isNotBlank()) {
                        Text(
                            text = current.year,
                            color = SwaySilver,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                    val durationText = when {
                        current.seasonsCount != null && current.seasonsCount > 0 ->
                            "${current.seasonsCount} ${if (current.seasonsCount == 1) "season" else "seasons"}"
                        current.runtime.isNotBlank() -> current.runtime
                        else -> ""
                    }
                    if (durationText.isNotBlank()) {
                        Text(
                            text = durationText,
                            color = SwaySilver,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                    if (current.status.isNotBlank()) {
                        Text(
                            text = current.status,
                            color = SwaySilver,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(Color.White.copy(alpha = 0.07f))
                            .padding(horizontal = 7.dp, vertical = 2.5.dp)
                    ) {
                        Text(
                            text = settings.defaultVideoQuality.label,
                            color = SwayIvory.copy(alpha = 0.80f),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                // 2. Monumental Cinema Display Headline
                Text(
                    text = current.title,
                    color = SwayIvory,
                    fontFamily = swayFonts.display,
                    fontSize = 40.sp,
                    lineHeight = 44.sp,
                    fontWeight = FontWeight.ExtraBold,
                    letterSpacing = (swayFonts.titleTrackingSp - 0.3f).sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )

                // 3. Natural Sentence-Case Genre Line
                if (current.genres.isNotEmpty()) {
                    Text(
                        text = current.genres.take(4).joinToString(", "),
                        color = theme.primaryColor,
                        fontSize = 13.5.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }

                // 4. Calm Editorial Synopsis (~60 chars per line, generous leading)
                Text(
                    text = current.overview.ifBlank {
                        "Select Details & 4K below to explore seasons, cast credits, and the trailer."
                    },
                    color = SwayIvory.copy(alpha = 0.84f),
                    fontSize = 15.sp,
                    lineHeight = 23.sp,
                    fontWeight = FontWeight.Normal,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 500.dp)
                )

                Spacer(Modifier.height(2.dp))

                // 5. Quiet Credits & Acclaim
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (!current.awards.isNullOrBlank()) {
                        Text(
                            text = current.awards,
                            color = theme.secondaryColor.copy(alpha = 0.90f),
                            fontSize = 12.5.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    if (current.cast.isNotEmpty()) {
                        Text(
                            text = "With ${current.cast.take(4).joinToString(", ")}",
                            color = SwaySilver,
                            fontSize = 12.5.sp,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }

        // ── Structural Hairline Divider + Tactile Action Bar ──
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(Color.White.copy(alpha = 0.08f))
            )

            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                val passKey = when (controlScheme) {
                    SwipeControlScheme.TINDER_3WAY -> "←"
                    SwipeControlScheme.BINARY_SPLIT -> "↓"
                    SwipeControlScheme.BUTTON_DOCK -> null
                }
                val listKey = when (controlScheme) {
                    SwipeControlScheme.TINDER_3WAY -> "→"
                    SwipeControlScheme.BINARY_SPLIT -> "←"
                    SwipeControlScheme.BUTTON_DOCK -> null
                }
                val watchedKey = when (controlScheme) {
                    SwipeControlScheme.TINDER_3WAY -> "↑"
                    SwipeControlScheme.BINARY_SPLIT -> "→"
                    SwipeControlScheme.BUTTON_DOCK -> null
                }
                val undoKey = when (controlScheme) {
                    SwipeControlScheme.TINDER_3WAY -> "↓"
                    SwipeControlScheme.BINARY_SPLIT, SwipeControlScheme.BUTTON_DOCK -> null
                }

                GlassPillButton(
                    text = "Pass",
                    keyHint = passKey,
                    accentColor = theme.passColor,
                    fontSize = 12.5.sp,
                    horizontalPadding = 11.dp,
                    verticalPadding = 8.dp,
                    onClick = onPass
                )

                GlassPillButton(
                    text = "Watchlist",
                    keyHint = listKey,
                    selected = true,
                    accentColor = theme.toWatchColor,
                    fontSize = 12.5.sp,
                    horizontalPadding = 11.dp,
                    verticalPadding = 8.dp,
                    onClick = onToWatch,
                    modifier = Modifier.focusRequester(dockButtonFocusRequester)
                )

                GlassPillButton(
                    text = "Watched",
                    keyHint = watchedKey,
                    accentColor = theme.watchedColor,
                    fontSize = 12.5.sp,
                    horizontalPadding = 11.dp,
                    verticalPadding = 8.dp,
                    onClick = onWatched
                )

                GlassPillButton(
                    text = "Details",
                    keyHint = "OK",
                    accentColor = theme.primaryColor,
                    fontSize = 12.5.sp,
                    horizontalPadding = 11.dp,
                    verticalPadding = 8.dp,
                    onClick = onDetails
                )

                Spacer(Modifier.weight(1f))

                GlassPillButton(
                    text = "Undo",
                    keyHint = undoKey,
                    selected = canUndo,
                    accentColor = if (canUndo) theme.secondaryColor else SwaySilver,
                    fontSize = 12.5.sp,
                    horizontalPadding = 11.dp,
                    verticalPadding = 8.dp,
                    onClick = onUndo
                )
            }
        }
    }
}

@Composable
private fun FilterDrawerModal(
    uiState: FlickDeckUiState,
    genres: List<String>,
    onSelectMediaType: (MediaType) -> Unit,
    onSelectCategory: (FeedCategory) -> Unit,
    onSelectGenre: (String) -> Unit,
    onSearchQueryChange: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val settings = LocalAppSettings.current
    val theme = settings.themePreset
    val firstFocus = remember { FocusRequester() }

    BackHandler(onBack = onDismiss)

    LaunchedEffect(Unit) {
        runCatching { firstFocus.requestFocus() }
    }

    Box(
        contentAlignment = Alignment.TopCenter,
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.68f))
            .modalScrimDismiss(onDismiss)
            .padding(top = 18.dp, start = 48.dp, end = 48.dp)
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier
                .fillMaxWidth()
                .liquidGlass(
                    theme = theme,
                    intensity = GlassIntensity.ULTRA_GLASS,
                    shape = RoundedCornerShape(24.dp),
                    isFocused = true
                )
                .consumeModalTouches()
                .padding(26.dp)
        ) {
            Row(
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = "Browse & filter deck",
                    color = SwayIvory,
                    fontFamily = LocalSwayFonts.current.display,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.ExtraBold
                )

                // Search input inside drawer
                val searchInteraction = remember { MutableInteractionSource() }
                val isSearchFocused by searchInteraction.collectIsFocusedAsState()
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .width(280.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color.Black.copy(alpha = 0.45f))
                        .border(
                            width = if (isSearchFocused) 1.5.dp else 1.dp,
                            color = if (isSearchFocused) SwayIvory else Color.White.copy(alpha = 0.16f),
                            shape = RoundedCornerShape(10.dp)
                        )
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Search,
                        contentDescription = null,
                        tint = SwaySilver,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Box(modifier = Modifier.weight(1f)) {
                        if (uiState.searchQuery.isEmpty()) {
                            Text(
                                text = "Search title...",
                                color = SwaySilver.copy(alpha = 0.7f),
                                fontSize = 13.sp
                            )
                        }
                        BasicTextField(
                            value = uiState.searchQuery,
                            onValueChange = onSearchQueryChange,
                            singleLine = true,
                            interactionSource = searchInteraction,
                            cursorBrush = SolidColor(theme.primaryColor),
                            textStyle = TextStyle(
                                color = SwayIvory,
                                fontFamily = LocalSwayFonts.current.body,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold
                            ),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                MediaType.entries.forEachIndexed { idx, type ->
                    GlassPillButton(
                        text = type.label,
                        selected = uiState.mediaType == type,
                        onClick = { onSelectMediaType(type) },
                        modifier = if (idx == 0) Modifier.focusRequester(firstFocus) else Modifier
                    )
                }
                Box(
                    Modifier
                        .width(1.dp)
                        .height(22.dp)
                        .background(Color.White.copy(alpha = 0.14f))
                )
                FeedCategory.entries.forEach { cat ->
                    GlassPillButton(
                        text = cat.label,
                        selected = uiState.feedCategory == cat && uiState.searchQuery.isBlank(),
                        accentColor = theme.secondaryColor,
                        onClick = { onSelectCategory(cat) }
                    )
                }
            }

            if (uiState.mediaType != MediaType.ANIME) {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(genres) { genre ->
                        GlassPillButton(
                            text = genre,
                            selected = uiState.selectedGenre == genre && uiState.searchQuery.isBlank(),
                            fontSize = 12.5.sp,
                            horizontalPadding = 13.dp,
                            verticalPadding = 7.dp,
                            onClick = {
                                onSelectGenre(genre)
                                onDismiss()
                            }
                        )
                    }
                }
            }

            Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                GlassPillButton(
                    text = "Done",
                    selected = true,
                    onClick = onDismiss
                )
            }
        }
    }
}

@Composable
private fun EmptyDeckFolio(
    searchQuery: String,
    hasSkipped: Boolean,
    canUndo: Boolean,
    onUndo: () -> Unit,
    onResetPassed: () -> Unit,
    onRefreshDeck: () -> Unit,
    onOpenFilters: () -> Unit,
    modifier: Modifier = Modifier
) {
    val settings = LocalAppSettings.current
    val theme = settings.themePreset
    val actionFocus = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        runCatching { actionFocus.requestFocus() }
    }

    Column(
        horizontalAlignment = Alignment.Start,
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = modifier
            .width(520.dp)
            .liquidGlass(theme = theme, intensity = settings.glassIntensity, shape = RoundedCornerShape(26.dp))
            .padding(32.dp)
    ) {
        Text(
            text = if (searchQuery.isNotBlank()) "No matches found" else "End of this reel",
            color = SwayIvory,
            fontFamily = LocalSwayFonts.current.display,
            fontSize = 28.sp,
            fontWeight = FontWeight.ExtraBold
        )
        Text(
            text = if (searchQuery.isNotBlank()) {
                "No untracked titles matched \"$searchQuery\". Try another search or clear filters."
            } else {
                "You have gone through every title matching this filter. Switch genres, reload the catalog, or restore passed shows."
            },
            color = SwaySilver,
            fontSize = 14.5.sp,
            lineHeight = 21.sp
        )
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            GlassPillButton(
                text = "Change filter",
                selected = true,
                onClick = onOpenFilters,
                modifier = Modifier.focusRequester(actionFocus)
            )
            GlassPillButton(
                text = if (searchQuery.isNotBlank()) "Clear search" else "Reload catalog",
                onClick = onRefreshDeck
            )
            if (canUndo) {
                GlassPillButton(
                    text = "Undo",
                    icon = Icons.Rounded.Replay,
                    accentColor = theme.secondaryColor,
                    onClick = onUndo
                )
            }
            if (hasSkipped) {
                GlassPillButton(
                    text = "Restore passed",
                    accentColor = theme.secondaryColor,
                    onClick = onResetPassed
                )
            }
        }
    }
}
