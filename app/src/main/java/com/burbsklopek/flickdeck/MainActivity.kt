package com.burbsklopek.flickdeck

import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Replay
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.*
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.burbsklopek.flickdeck.model.SwipeAction
import com.burbsklopek.flickdeck.ui.screens.*
import com.burbsklopek.flickdeck.ui.theme.*
import com.burbsklopek.flickdeck.viewmodel.FlickDeckUiState
import com.burbsklopek.flickdeck.viewmodel.FlickDeckViewModel
import com.burbsklopek.flickdeck.viewmodel.NavTab

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestHighRefreshRate()
        setContent {
            val vm: FlickDeckViewModel = viewModel()
            val uiState by vm.uiState.collectAsStateWithLifecycle()
            val swayFonts = remember(uiState.settings.fontPairing) {
                resolveSwayFonts(uiState.settings.fontPairing)
            }
            val materialTypography = remember(swayFonts) {
                buildSwayMaterialTypography(swayFonts)
            }

            MaterialTheme(typography = materialTypography) {
                CompositionLocalProvider(
                    LocalAppSettings provides uiState.settings,
                    LocalSwayFonts provides swayFonts
                ) {
                    ProvideTextStyle(TextStyle(fontFamily = swayFonts.body)) {
                        SwayRootShell(
                            uiState = uiState,
                            vm = vm
                        )
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        requestHighRefreshRate()
    }

    private fun requestHighRefreshRate() {
        runCatching {
            val activeDisplay = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                display
            } else {
                @Suppress("DEPRECATION")
                windowManager.defaultDisplay
            } ?: return

            val currentMode = activeDisplay.mode
            val bestMode = activeDisplay.supportedModes
                .filter {
                    it.physicalWidth == currentMode.physicalWidth &&
                        it.physicalHeight == currentMode.physicalHeight
                }
                .maxByOrNull { it.refreshRate }

            val params = window.attributes
            if (bestMode != null) {
                if (bestMode.refreshRate > currentMode.refreshRate) {
                    params.preferredDisplayModeId = bestMode.modeId
                }
                params.preferredRefreshRate = bestMode.refreshRate
            }
            window.attributes = params
        }
    }
}

@Composable
private fun SwayRootShell(
    uiState: FlickDeckUiState,
    vm: FlickDeckViewModel
) {
    val settings = uiState.settings
    val theme = settings.themePreset
    val topNavFocusRequester = remember { FocusRequester() }
    var showFilterSheet by remember { mutableStateOf(false) }

    // TV Back navigation: return from Collection / Sync / Settings back to Deck instead of exiting app
    BackHandler(enabled = uiState.activeTab != NavTab.DECK) {
        vm.selectTab(NavTab.DECK)
    }

    val activeBackdropUrl = remember(uiState.activeTab, uiState.deckCards, uiState.toWatchEntries) {
        when (uiState.activeTab) {
            NavTab.DECK -> uiState.deckCards.firstOrNull()?.backdropUrl.orEmpty()
            NavTab.LIBRARY -> uiState.toWatchEntries.firstOrNull()?.item?.backdropUrl
                ?: uiState.watchedEntries.firstOrNull()?.item?.backdropUrl.orEmpty()
            else -> uiState.deckCards.firstOrNull()?.backdropUrl.orEmpty()
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        // 1. Dynamic 4K Hardware-Blurred Cinema Backdrop Stage
        BackdropAuraStage(
            backdropUrl = activeBackdropUrl,
            settings = settings
        )

        // 2. Foreground Stage with Whisper-Clean Single Top Rail
        Column(modifier = Modifier.fillMaxSize()) {
            QuietTopRail(
                uiState = uiState,
                firstNavFocusRequester = topNavFocusRequester,
                onSelectTab = { tab ->
                    showFilterSheet = false
                    vm.selectTab(tab)
                },
                onUndo = vm::undoLastSwipe,
                onOpenFilters = { showFilterSheet = !showFilterSheet }
            )

            Box(modifier = Modifier.weight(1f)) {
                when (uiState.activeTab) {
                    NavTab.DECK -> SwipeDeckScreen(
                        uiState = uiState,
                        genres = vm.genres,
                        showFilterSheet = showFilterSheet,
                        onToggleFilterSheet = { showFilterSheet = it },
                        topNavFocusRequester = topNavFocusRequester,
                        onSelectMediaType = vm::selectMediaType,
                        onSelectCategory = vm::selectFeedCategory,
                        onSelectGenre = vm::selectGenre,
                        onSearchQueryChange = vm::updateSearchQuery,
                        onSwipe = vm::onSwipeCard,
                        onUndo = vm::undoLastSwipe,
                        onClearRewind = vm::clearRewindTrigger,
                        onOpenDetails = vm::openDetails,
                        onResetPassed = vm::resetPassedItems,
                        onRefreshDeck = { vm.refreshDeck(reset = true) }
                    )

                    NavTab.LIBRARY -> LibraryScreen(
                        uiState = uiState,
                        onMoveEntry = vm::moveLibraryEntry,
                        onRemoveEntry = vm::removeLibraryEntry,
                        onOpenDetails = vm::openDetails
                    )

                    NavTab.SYNC -> SyncHubScreen(
                        uiState = uiState,
                        onStartAuth = vm::startProviderDeviceAuth,
                        onDisconnect = vm::disconnectProvider,
                        onCancelAuth = vm::cancelDeviceAuth,
                        onSyncNow = { vm.syncNow(silent = false) },
                        onUpdateDrafts = vm::updateCredentialDrafts,
                        onSaveDrafts = vm::saveDraftCredentials
                    )

                    NavTab.THEMES -> ThemeSettingsScreen(
                        settings = settings,
                        onUpdateSettings = vm::updateSettings,
                        onResetPassed = vm::resetPassedItems
                    )
                }
            }
        }

        // 3. Card Detail & 2160p Trailer Modal
        val detailItem = uiState.detailModalItem
        if (detailItem != null) {
            val trackedEntry = uiState.trackedMap[detailItem.id]
            CardDetailModal(
                item = detailItem,
                currentAction = trackedEntry?.action,
                currentRating = trackedEntry?.userRating,
                onDismiss = vm::closeDetails,
                onMarkWatched = { vm.onSwipeCard(detailItem, SwipeAction.WATCHED) },
                onMarkToWatch = { vm.onSwipeCard(detailItem, SwipeAction.TO_WATCH) },
                onPass = { vm.onSwipeCard(detailItem, SwipeAction.SKIPPED) },
                onRate = { score -> vm.rateMediaItem(detailItem, score) }
            )
        }

        // 4. Disciplined Floating Confirmation Toast
        val toast = uiState.toast
        AnimatedVisibility(
            visible = toast != null,
            enter = fadeIn() + slideInVertically { it / 2 },
            exit = fadeOut() + slideOutVertically { it / 2 },
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(start = 54.dp, bottom = 22.dp)
        ) {
            if (toast != null) {
                val toastColor = when (toast.accentType) {
                    SwipeAction.WATCHED -> theme.watchedColor
                    SwipeAction.TO_WATCH -> theme.toWatchColor
                    SwipeAction.SKIPPED -> theme.passColor
                    null -> theme.primaryColor
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .shadow(16.dp, RoundedCornerShape(12.dp))
                        .clip(RoundedCornerShape(12.dp))
                        .background(SwayObsidianInk.copy(alpha = 0.92f))
                        .border(1.dp, toastColor.copy(alpha = 0.7f), RoundedCornerShape(12.dp))
                        .padding(horizontal = 16.dp, vertical = 9.dp)
                ) {
                    Box(
                        Modifier
                            .size(7.dp)
                            .clip(CircleShape)
                            .background(toastColor)
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = toast.text,
                        color = SwayIvory,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }
    }
}

@Composable
private fun QuietTopRail(
    uiState: FlickDeckUiState,
    firstNavFocusRequester: FocusRequester,
    onSelectTab: (NavTab) -> Unit,
    onUndo: () -> Unit,
    onOpenFilters: () -> Unit
) {
    val theme = uiState.settings.themePreset
    val collectionCount = uiState.watchedEntries.size + uiState.toWatchEntries.size

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 48.dp, vertical = 18.dp)
    ) {
        // Left: Sway Brand Identity + Quiet Tab Bar
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(28.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Image(
                    painter = painterResource(id = R.drawable.sway_logo_mark),
                    contentDescription = "Sway",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(34.dp)
                        .clip(RoundedCornerShape(9.dp))
                        .border(1.dp, Color.White.copy(alpha = 0.22f), RoundedCornerShape(9.dp))
                )
                Text(
                    text = "Sway",
                    color = SwayIvory,
                    fontFamily = LocalSwayFonts.current.display,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.ExtraBold,
                    letterSpacing = (-0.6).sp
                )
            }

            // Quiet Editorial Tab Items (replaces heavy multi-pill navbar)
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TopRailTab(
                    label = "Deck",
                    selected = uiState.activeTab == NavTab.DECK,
                    onClick = { onSelectTab(NavTab.DECK) },
                    modifier = Modifier.focusRequester(firstNavFocusRequester)
                )
                TopRailTab(
                    label = if (collectionCount > 0) "Collection  $collectionCount" else "Collection",
                    selected = uiState.activeTab == NavTab.LIBRARY,
                    onClick = { onSelectTab(NavTab.LIBRARY) }
                )
                TopRailTab(
                    label = "Sync",
                    selected = uiState.activeTab == NavTab.SYNC,
                    dotConnected = uiState.traktAccount.isConnected || uiState.simklAccount.isConnected,
                    onClick = { onSelectTab(NavTab.SYNC) }
                )
                TopRailTab(
                    label = "Settings",
                    selected = uiState.activeTab == NavTab.THEMES,
                    onClick = { onSelectTab(NavTab.THEMES) }
                )
            }
        }

        // Right: Single contextual Filter trigger when on Deck, or subtle sync summary elsewhere
        if (uiState.activeTab == NavTab.DECK) {
            val filterSummary = buildList {
                if (uiState.searchQuery.isNotBlank()) {
                    add("\"${uiState.searchQuery}\"")
                } else {
                    add(uiState.mediaType.label)
                    add(uiState.feedCategory.label)
                    if (uiState.selectedGenre != "All") add(uiState.selectedGenre)
                }
            }.joinToString("  /  ")

            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                val canUndo = uiState.undoStack.isNotEmpty()
                GlassPillButton(
                    text = "Undo",
                    icon = Icons.Rounded.Replay,
                    selected = canUndo,
                    accentColor = if (canUndo) theme.secondaryColor else SwaySilver,
                    fontSize = 12.5.sp,
                    horizontalPadding = 13.dp,
                    verticalPadding = 7.dp,
                    onClick = onUndo
                )
                GlassPillButton(
                    text = filterSummary,
                    icon = Icons.Rounded.Tune,
                    accentColor = theme.primaryColor,
                    fontSize = 12.5.sp,
                    horizontalPadding = 14.dp,
                    verticalPadding = 7.dp,
                    onClick = onOpenFilters
                )
            }
        } else {
            val activeSyncText = when {
                uiState.traktAccount.isConnected && uiState.simklAccount.isConnected -> "Trakt & Simkl synced"
                uiState.traktAccount.isConnected -> "Trakt synced"
                uiState.simklAccount.isConnected -> "Simkl synced"
                else -> "Local library"
            }
            Text(
                text = activeSyncText,
                color = SwaySilver,
                fontSize = 12.5.sp,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

@Composable
private fun TopRailTab(
    label: String,
    selected: Boolean,
    dotConnected: Boolean = false,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val theme = LocalAppSettings.current.themePreset
    val interaction = remember { MutableInteractionSource() }
    val isFocused by interaction.collectIsFocusedAsState()

    val bg = when {
        isFocused -> SwayIvory
        selected -> Color.White.copy(alpha = 0.11f)
        else -> Color.Transparent
    }
    val textColor = when {
        isFocused -> SwayObsidianInk
        selected -> SwayIvory
        else -> SwaySilver
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(bg)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 7.dp)
    ) {
        Text(
            text = label,
            color = textColor,
            fontSize = 14.sp,
            fontWeight = if (selected || isFocused) FontWeight.Bold else FontWeight.Medium
        )
        if (dotConnected) {
            Spacer(Modifier.width(6.dp))
            Box(
                Modifier
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(theme.primaryColor)
            )
        }
    }
}
