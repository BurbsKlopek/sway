package com.burbsklopek.flickdeck.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.burbsklopek.flickdeck.data.companion.CompanionSetupServer
import com.burbsklopek.flickdeck.data.local.FlickDeckStore
import com.burbsklopek.flickdeck.data.local.StoredCredentials
import com.burbsklopek.flickdeck.data.remote.*
import com.burbsklopek.flickdeck.model.*
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

enum class NavTab(val label: String) {
    DECK("Swipe Deck"),
    LIBRARY("My Library"),
    SYNC("Trakt & Simkl"),
    THEMES("Settings")
}

data class UiBannerToast(
    val id: Long = System.nanoTime(),
    val text: String,
    val accentType: SwipeAction? = null
)

data class FlickDeckUiState(
    val activeTab: NavTab = NavTab.DECK,
    val mediaType: MediaType = MediaType.SHOW,
    val feedCategory: FeedCategory = FeedCategory.TRENDING,
    val selectedGenre: String = "All",
    val searchQuery: String = "",
    val isLoadingDeck: Boolean = true,
    val isFetchingMore: Boolean = false,
    val rawCatalog: List<MediaItem> = emptyList(),
    val deckCards: List<MediaItem> = emptyList(),
    val trackedMap: Map<String, TrackedEntry> = emptyMap(),
    val undoStack: List<SwipeHistoryStep> = emptyList(),
    val rewindTriggerItem: MediaItem? = null,
    val detailModalItem: MediaItem? = null,
    val settings: AppSettings = AppSettings(),
    val traktAccount: ProviderAccountState = ProviderAccountState(SyncProvider.TRAKT),
    val simklAccount: ProviderAccountState = ProviderAccountState(SyncProvider.SIMKL),
    val pendingSyncCount: Int = 0,
    val isSyncingNow: Boolean = false,
    val activeAuthSession: DeviceAuthSession? = null,
    val authError: String? = null,
    val companionUrl: String? = null,
    val toast: UiBannerToast? = null,
    val traktClientIdDraft: String = "",
    val traktSecretDraft: String = "",
    val simklClientIdDraft: String = ""
) {
    val watchedEntries: List<TrackedEntry>
        get() = trackedMap.values
            .filter { it.action == SwipeAction.WATCHED }
            .sortedByDescending { it.updatedAtMs }

    val toWatchEntries: List<TrackedEntry>
        get() = trackedMap.values
            .filter { it.action == SwipeAction.TO_WATCH }
            .sortedByDescending { it.updatedAtMs }

    val skippedEntries: List<TrackedEntry>
        get() = trackedMap.values
            .filter { it.action == SwipeAction.SKIPPED }
            .sortedByDescending { it.updatedAtMs }
}

class FlickDeckViewModel(application: Application) : AndroidViewModel(application) {
    private val store = FlickDeckStore(application)
    private val catalogService = CatalogService()
    private val traktApi = TraktApiClient()
    private val simklApi = SimklApiClient()

    private var credentials: StoredCredentials = store.loadCredentials()
    private var pendingOps: MutableList<PendingSyncOp> = store.loadPendingSyncOps().toMutableList()
    private var nextCatalogPage: Int = 0
    private var catalogExhausted: Boolean = false
    private var deckGeneration: Long = 0L
    private var deckFetchJob: Job? = null
    private var authPollJob: Job? = null
    private var toastClearJob: Job? = null
    private val enrichedIds = HashSet<String>()
    private val inFlightEnrichIds = HashSet<String>()

    private val companionServer = CompanionSetupServer(
        getCurrentCredentials = { credentials },
        onCredentialsSubmitted = { traktId, traktSecret, simklId, tmdbKey ->
            viewModelScope.launch {
                saveApiCredentials(traktId, traktSecret, simklId, tmdbKey)
                postToast("API keys updated from phone!")
            }
        }
    )

    private val _uiState = MutableStateFlow(
        FlickDeckUiState(
            settings = store.loadSettings(),
            trackedMap = store.loadTrackedEntries(),
            pendingSyncCount = pendingOps.size,
            traktAccount = buildTraktAccountState(credentials),
            simklAccount = buildSimklAccountState(credentials),
            traktClientIdDraft = credentials.traktClientId,
            traktSecretDraft = credentials.traktClientSecret,
            simklClientIdDraft = credentials.simklClientId
        )
    )
    val uiState: StateFlow<FlickDeckUiState> = _uiState.asStateFlow()

    val genres: List<String> = catalogService.supportedGenres

    init {
        refreshDeck(reset = true)
        if (credentials.traktAccessToken.isNotBlank() || credentials.simklAccessToken.isNotBlank()) {
            syncNow(silent = true)
        }
    }

    fun selectTab(tab: NavTab) {
        _uiState.update { it.copy(activeTab = tab) }
        if (tab == NavTab.SYNC) {
            val url = companionServer.start()
            _uiState.update { it.copy(companionUrl = url) }
        } else {
            companionServer.stop()
        }
    }

    fun selectMediaType(type: MediaType) {
        if (_uiState.value.mediaType == type) return
        _uiState.update { it.copy(mediaType = type, searchQuery = "") }
        refreshDeck(reset = true)
    }

    fun selectFeedCategory(category: FeedCategory) {
        if (_uiState.value.feedCategory == category) return
        _uiState.update { it.copy(feedCategory = category, searchQuery = "") }
        refreshDeck(reset = true)
    }

    fun selectGenre(genre: String) {
        if (_uiState.value.selectedGenre == genre) return
        _uiState.update { it.copy(selectedGenre = genre, searchQuery = "") }
        refreshDeck(reset = true)
    }

    fun updateSearchQuery(query: String) {
        _uiState.update { it.copy(searchQuery = query) }
        if (query.isBlank()) {
            refreshDeck(reset = true)
            return
        }
        val gen = ++deckGeneration
        deckFetchJob?.cancel()
        deckFetchJob = viewModelScope.launch {
            delay(250L)
            if (gen != deckGeneration) return@launch
            _uiState.update { it.copy(isLoadingDeck = true, isFetchingMore = false) }
            val results = catalogService.searchCatalog(query, _uiState.value.mediaType)
            if (gen != deckGeneration) return@launch
            _uiState.update { state ->
                val filtered = computeFilteredDeck(results, state.trackedMap, state.settings.hideAlreadyTracked)
                state.copy(
                    isLoadingDeck = false,
                    isFetchingMore = false,
                    rawCatalog = results,
                    deckCards = filtered
                )
            }
            enrichTopCards()
        }
    }

    fun refreshDeck(reset: Boolean) {
        if (reset) {
            val gen = ++deckGeneration
            deckFetchJob?.cancel()
            nextCatalogPage = 0
            catalogExhausted = false
            _uiState.update {
                it.copy(
                    isLoadingDeck = true,
                    isFetchingMore = false,
                    rawCatalog = emptyList(),
                    deckCards = emptyList()
                )
            }
            deckFetchJob = viewModelScope.launch {
                fetchUntilBuffered(gen = gen, isInitialReset = true)
            }
        } else {
            replenishDeckIfNeeded()
        }
    }

    private fun replenishDeckIfNeeded() {
        val state = _uiState.value
        if (catalogExhausted || state.searchQuery.isNotBlank()) return
        if (deckFetchJob?.isActive == true) return
        if (state.deckCards.size > DeckFilterEngine.PREFETCH_TRIGGER_CARDS) return

        val gen = deckGeneration
        _uiState.update { it.copy(isFetchingMore = true) }
        deckFetchJob = viewModelScope.launch {
            fetchUntilBuffered(gen = gen, isInitialReset = false)
        }
    }

    private suspend fun fetchUntilBuffered(gen: Long, isInitialReset: Boolean) {
        try {
            var pagesFetched = 0
            var consecutiveDuplicatePages = 0

            while (!catalogExhausted) {
                if (gen != deckGeneration) return
                val snapshot = _uiState.value
                if (snapshot.searchQuery.isNotBlank()) return

                val maxBurst = if (snapshot.deckCards.isEmpty()) {
                    DeckFilterEngine.MAX_PAGES_WHEN_EMPTY
                } else {
                    DeckFilterEngine.MAX_PAGES_PER_BURST
                }
                if (pagesFetched >= maxBurst) break

                val pageToFetch = nextCatalogPage
                val combined = LinkedHashMap<String, MediaItem>()

                if (pageToFetch == 0 && snapshot.feedCategory == FeedCategory.FOR_YOU && snapshot.selectedGenre == "All") {
                    if (credentials.traktClientId.isNotBlank()) {
                        traktApi.fetchDiscovery(
                            clientId = credentials.traktClientId,
                            accessToken = credentials.traktAccessToken.takeIf { it.isNotBlank() },
                            category = FeedCategory.FOR_YOU,
                            mediaType = snapshot.mediaType
                        ).forEach { combined[it.id] = it }
                    }
                    if (credentials.simklClientId.isNotBlank()) {
                        simklApi.fetchDiscovery(
                            clientId = credentials.simklClientId,
                            mediaType = snapshot.mediaType
                        ).forEach { combined.putIfAbsent(it.id, it) }
                    }
                }

                val catalogBatch = catalogService.fetchCatalogPage(
                    mediaType = snapshot.mediaType,
                    category = snapshot.feedCategory,
                    genre = snapshot.selectedGenre,
                    pageIndex = pageToFetch
                )
                catalogBatch.forEach { combined.putIfAbsent(it.id, it) }

                if (gen != deckGeneration) return
                nextCatalogPage = pageToFetch + 1
                pagesFetched++

                var visibleCountAfterMerge = 0

                _uiState.update { cur ->
                    if (gen != deckGeneration) return@update cur
                    val existingIds = cur.rawCatalog.asSequence().map { it.id }.toHashSet()
                    val newItems = combined.values.filter { it.id !in existingIds }

                    if (catalogBatch.isEmpty() && combined.isEmpty()) {
                        catalogExhausted = true
                    } else if (newItems.isEmpty()) {
                        consecutiveDuplicatePages++
                        if (consecutiveDuplicatePages >= 2) {
                            catalogExhausted = true
                        }
                    } else {
                        consecutiveDuplicatePages = 0
                    }

                    val mergedRaw = if (pageToFetch == 0 && isInitialReset && cur.rawCatalog.isEmpty()) {
                        combined.values.toList()
                    } else {
                        cur.rawCatalog + newItems
                    }
                    val visible = computeFilteredDeck(mergedRaw, cur.trackedMap, cur.settings.hideAlreadyTracked)
                    visibleCountAfterMerge = visible.size

                    val nextMaxBurst = if (visible.isEmpty()) {
                        DeckFilterEngine.MAX_PAGES_WHEN_EMPTY
                    } else {
                        DeckFilterEngine.MAX_PAGES_PER_BURST
                    }
                    val willContinue = !catalogExhausted &&
                        visible.size < DeckFilterEngine.MIN_BUFFER_CARDS &&
                        pagesFetched < nextMaxBurst

                    cur.copy(
                        isLoadingDeck = if (visible.isNotEmpty() || !willContinue) false else cur.isLoadingDeck,
                        isFetchingMore = willContinue,
                        rawCatalog = mergedRaw,
                        deckCards = visible
                    )
                }

                if (visibleCountAfterMerge >= DeckFilterEngine.MIN_BUFFER_CARDS || catalogExhausted) {
                    break
                }
            }
        } finally {
            if (gen == deckGeneration) {
                _uiState.update { it.copy(isLoadingDeck = false, isFetchingMore = false) }
                enrichTopCards()
            }
        }
    }

    private fun enrichTopCards() {
        val topCandidates = _uiState.value.deckCards.take(4)
        topCandidates.forEach { card ->
            if (card.id !in enrichedIds && inFlightEnrichIds.add(card.id)) {
                viewModelScope.launch {
                    try {
                        val enriched = catalogService.enrichItemDetails(card)
                        if (enriched != null) {
                            enrichedIds.add(card.id)
                            if (enriched != card) {
                                _uiState.update { cur ->
                                    val newRaw = cur.rawCatalog.map { if (it.id == card.id) enriched else it }
                                    val newDeck = cur.deckCards.map { if (it.id == card.id) enriched else it }
                                    val newModal = if (cur.detailModalItem?.id == card.id) enriched else cur.detailModalItem
                                    cur.copy(rawCatalog = newRaw, deckCards = newDeck, detailModalItem = newModal)
                                }
                            }
                        }
                    } finally {
                        inFlightEnrichIds.remove(card.id)
                    }
                }
            }
        }
    }

    fun onSwipeCard(item: MediaItem, action: SwipeAction) {
        val state = _uiState.value
        val previous = findMatchingTracked(item, state.trackedMap)
        val entry = TrackedEntry(
            item = item,
            action = action,
            updatedAtMs = System.currentTimeMillis(),
            userRating = previous?.userRating,
            syncedTrakt = false,
            syncedSimkl = false
        )

        val updatedMap = state.trackedMap.toMutableMap()
        if (previous != null && previous.item.id != item.id) {
            updatedMap.remove(previous.item.id)
        }
        updatedMap[item.id] = entry
        store.saveTrackedEntries(updatedMap)

        val historyStep = SwipeHistoryStep(
            item = item,
            appliedAction = action,
            previousEntry = previous
        )

        val bannerLabel = when (action) {
            SwipeAction.WATCHED -> "Marked watched: ${item.title}"
            SwipeAction.TO_WATCH -> "Added to Watchlist: ${item.title}"
            SwipeAction.SKIPPED -> "Passed on ${item.title}"
        }

        _uiState.update { cur ->
            val nextDeck = cur.deckCards.filterNot { it.id == item.id }
            val canFetchMore = !catalogExhausted && cur.searchQuery.isBlank()
            val willPrefetch = canFetchMore && nextDeck.size <= DeckFilterEngine.PREFETCH_TRIGGER_CARDS
            cur.copy(
                trackedMap = updatedMap,
                deckCards = nextDeck,
                isFetchingMore = cur.isFetchingMore || willPrefetch,
                undoStack = (listOf(historyStep) + cur.undoStack).take(30),
                detailModalItem = if (cur.detailModalItem?.id == item.id) null else cur.detailModalItem
            )
        }
        postToast(bannerLabel, action)

        // Queue remote sync for Watched / To-Watch
        when (action) {
            SwipeAction.WATCHED -> enqueueForActiveProviders(item, SyncOpType.ADD_WATCHED)
            SwipeAction.TO_WATCH -> enqueueForActiveProviders(item, SyncOpType.ADD_WATCHLIST)
            SwipeAction.SKIPPED -> Unit
        }

        replenishDeckIfNeeded()
        enrichTopCards()
    }

    fun undoLastSwipe() {
        val state = _uiState.value
        val last = state.undoStack.firstOrNull() ?: run {
            postToast("Nothing left to undo")
            return
        }

        val updatedMap = state.trackedMap.toMutableMap()
        if (last.previousEntry != null) {
            updatedMap[last.previousEntry.item.id] = last.previousEntry
        } else {
            updatedMap.remove(last.item.id)
        }
        store.saveTrackedEntries(updatedMap)

        _uiState.update { cur ->
            val restoredDeck = listOf(last.item) + cur.deckCards.filterNot { it.id == last.item.id }
            cur.copy(
                trackedMap = updatedMap,
                deckCards = restoredDeck,
                undoStack = cur.undoStack.drop(1),
                rewindTriggerItem = last.item
            )
        }
        postToast("Rewound ${last.item.title}")

        when (last.appliedAction) {
            SwipeAction.WATCHED -> enqueueForActiveProviders(last.item, SyncOpType.REMOVE_WATCHED)
            SwipeAction.TO_WATCH -> enqueueForActiveProviders(last.item, SyncOpType.REMOVE_WATCHLIST)
            SwipeAction.SKIPPED -> Unit
        }
    }

    fun clearRewindTrigger() {
        _uiState.update { it.copy(rewindTriggerItem = null) }
    }

    fun openDetails(item: MediaItem) {
        _uiState.update { it.copy(detailModalItem = item) }
        viewModelScope.launch {
            val enriched = catalogService.enrichItemDetails(item)
            if (enriched != null && enriched != item) {
                _uiState.update { cur ->
                    if (cur.detailModalItem?.id == item.id) cur.copy(detailModalItem = enriched) else cur
                }
            }
        }
    }

    fun closeDetails() {
        _uiState.update { it.copy(detailModalItem = null) }
    }

    fun rateMediaItem(item: MediaItem, rating: Int) {
        val clamped = rating.coerceIn(1, 10)
        val state = _uiState.value
        val existing = findMatchingTracked(item, state.trackedMap)
        val updatedEntry = (existing ?: TrackedEntry(item = item, action = SwipeAction.WATCHED)).copy(
            userRating = clamped,
            updatedAtMs = System.currentTimeMillis()
        )
        val updatedMap = state.trackedMap.toMutableMap().apply { put(updatedEntry.item.id, updatedEntry) }
        store.saveTrackedEntries(updatedMap)
        _uiState.update { it.copy(trackedMap = updatedMap) }
        postToast("★ Rated ${item.title} $clamped/10", SwipeAction.WATCHED)
        enqueueForActiveProviders(item, SyncOpType.RATE, rating = clamped)
    }

    fun moveLibraryEntry(entry: TrackedEntry, newAction: SwipeAction) {
        val updated = entry.copy(
            action = newAction,
            updatedAtMs = System.currentTimeMillis(),
            syncedTrakt = false,
            syncedSimkl = false
        )
        val map = _uiState.value.trackedMap.toMutableMap().apply { put(entry.item.id, updated) }
        store.saveTrackedEntries(map)
        _uiState.update { cur ->
            cur.copy(
                trackedMap = map,
                deckCards = computeFilteredDeck(cur.rawCatalog, map, cur.settings.hideAlreadyTracked)
            )
        }
        when (newAction) {
            SwipeAction.WATCHED -> enqueueForActiveProviders(entry.item, SyncOpType.ADD_WATCHED)
            SwipeAction.TO_WATCH -> enqueueForActiveProviders(entry.item, SyncOpType.ADD_WATCHLIST)
            SwipeAction.SKIPPED -> Unit
        }
        postToast("Moved ${entry.item.title} → ${newAction.label}", newAction)
    }

    fun removeLibraryEntry(entry: TrackedEntry) {
        val map = _uiState.value.trackedMap.toMutableMap().apply { remove(entry.item.id) }
        store.saveTrackedEntries(map)
        _uiState.update { cur ->
            cur.copy(
                trackedMap = map,
                deckCards = computeFilteredDeck(cur.rawCatalog, map, cur.settings.hideAlreadyTracked)
            )
        }
        when (entry.action) {
            SwipeAction.WATCHED -> enqueueForActiveProviders(entry.item, SyncOpType.REMOVE_WATCHED)
            SwipeAction.TO_WATCH -> enqueueForActiveProviders(entry.item, SyncOpType.REMOVE_WATCHLIST)
            SwipeAction.SKIPPED -> Unit
        }
        postToast("Removed ${entry.item.title}")
    }

    fun resetPassedItems() {
        val map = _uiState.value.trackedMap.filterValues { it.action != SwipeAction.SKIPPED }
        store.saveTrackedEntries(map)
        _uiState.update { cur ->
            cur.copy(
                trackedMap = map,
                deckCards = computeFilteredDeck(cur.rawCatalog, map, cur.settings.hideAlreadyTracked)
            )
        }
        postToast("Cleared all passed shows back into deck")
    }

    fun updateSettings(transform: (AppSettings) -> AppSettings) {
        val next = transform(_uiState.value.settings)
        store.saveSettings(next)
        _uiState.update { cur ->
            val visible = computeFilteredDeck(cur.rawCatalog, cur.trackedMap, next.hideAlreadyTracked)
            val canFetchMore = !catalogExhausted && cur.searchQuery.isBlank()
            cur.copy(
                settings = next,
                deckCards = visible,
                isFetchingMore = cur.isFetchingMore || (canFetchMore && visible.size <= DeckFilterEngine.PREFETCH_TRIGGER_CARDS)
            )
        }
        replenishDeckIfNeeded()
    }

    // ── Credentials & OAuth Device Flows ──

    fun updateCredentialDrafts(traktId: String? = null, traktSecret: String? = null, simklId: String? = null) {
        _uiState.update { cur ->
            cur.copy(
                traktClientIdDraft = traktId ?: cur.traktClientIdDraft,
                traktSecretDraft = traktSecret ?: cur.traktSecretDraft,
                simklClientIdDraft = simklId ?: cur.simklClientIdDraft
            )
        }
    }

    fun saveDraftCredentials() {
        val s = _uiState.value
        saveApiCredentials(
            traktId = s.traktClientIdDraft,
            traktSecret = s.traktSecretDraft,
            simklId = s.simklClientIdDraft,
            tmdbKey = credentials.tmdbApiKey
        )
        postToast("Saved API credentials")
    }

    private fun saveApiCredentials(traktId: String, traktSecret: String, simklId: String, tmdbKey: String) {
        credentials = credentials.copy(
            traktClientId = traktId.trim(),
            traktClientSecret = traktSecret.trim(),
            simklClientId = simklId.trim(),
            tmdbApiKey = tmdbKey.trim()
        )
        store.saveCredentials(credentials)
        _uiState.update { cur ->
            cur.copy(
                traktAccount = buildTraktAccountState(credentials),
                simklAccount = buildSimklAccountState(credentials),
                traktClientIdDraft = credentials.traktClientId,
                traktSecretDraft = credentials.traktClientSecret,
                simklClientIdDraft = credentials.simklClientId
            )
        }
    }

    fun startProviderDeviceAuth(provider: SyncProvider) {
        authPollJob?.cancel()
        _uiState.update { it.copy(authError = null, activeAuthSession = null) }

        viewModelScope.launch {
            when (provider) {
                SyncProvider.TRAKT -> {
                    if (credentials.traktClientId.isBlank() || credentials.traktClientSecret.isBlank()) {
                        _uiState.update {
                            it.copy(authError = "Enter your Trakt Client ID & Secret first (scan the Phone Companion QR on the right!).")
                        }
                        return@launch
                    }
                    val res = traktApi.requestDeviceCode(credentials.traktClientId)
                    res.fold(
                        onSuccess = { session ->
                            _uiState.update { it.copy(activeAuthSession = session) }
                            pollTraktSession(session)
                        },
                        onFailure = { err ->
                            _uiState.update { it.copy(authError = err.message ?: "Failed to get Trakt code") }
                        }
                    )
                }
                SyncProvider.SIMKL -> {
                    if (credentials.simklClientId.isBlank()) {
                        _uiState.update {
                            it.copy(authError = "Enter your Simkl Client ID first (scan the Phone Companion QR on the right!).")
                        }
                        return@launch
                    }
                    val res = simklApi.requestDeviceCode(credentials.simklClientId)
                    res.fold(
                        onSuccess = { session ->
                            _uiState.update { it.copy(activeAuthSession = session) }
                            pollSimklSession(session)
                        },
                        onFailure = { err ->
                            _uiState.update { it.copy(authError = err.message ?: "Failed to get Simkl PIN") }
                        }
                    )
                }
            }
        }
    }

    fun cancelDeviceAuth() {
        authPollJob?.cancel()
        _uiState.update { it.copy(activeAuthSession = null, authError = null) }
    }

    fun disconnectProvider(provider: SyncProvider) {
        credentials = when (provider) {
            SyncProvider.TRAKT -> credentials.copy(
                traktAccessToken = "",
                traktRefreshToken = "",
                traktUsername = "",
                traktAvatarUrl = null
            )
            SyncProvider.SIMKL -> credentials.copy(
                simklAccessToken = "",
                simklRefreshToken = "",
                simklUsername = "",
                simklAvatarUrl = null
            )
        }
        store.saveCredentials(credentials)
        _uiState.update {
            it.copy(
                traktAccount = buildTraktAccountState(credentials),
                simklAccount = buildSimklAccountState(credentials)
            )
        }
        postToast("Disconnected ${provider.displayName}")
    }

    private fun pollTraktSession(session: DeviceAuthSession) {
        authPollJob = viewModelScope.launch {
            var intervalMs = (session.intervalSec.coerceAtLeast(5)) * 1000L
            val deadline = session.startedAtMs + session.expiresInSec * 1000L
            while (System.currentTimeMillis() < deadline) {
                delay(intervalMs)
                when (val poll = traktApi.pollDeviceToken(
                    credentials.traktClientId,
                    credentials.traktClientSecret,
                    session.deviceCode
                )) {
                    is PollTokenResult.Pending -> Unit
                    is PollTokenResult.SlowDown -> intervalMs += 5000L
                    is PollTokenResult.ExpiredOrDenied -> {
                        _uiState.update { it.copy(activeAuthSession = null, authError = poll.reason) }
                        return@launch
                    }
                    is PollTokenResult.Authorized -> {
                        val profile = traktApi.fetchUserProfile(credentials.traktClientId, poll.accessToken).getOrNull()
                        credentials = credentials.copy(
                            traktAccessToken = poll.accessToken,
                            traktRefreshToken = poll.refreshToken,
                            traktExpiresAtMs = System.currentTimeMillis() + poll.expiresInSec * 1000L,
                            traktUsername = profile?.username ?: "Connected",
                            traktAvatarUrl = profile?.avatarUrl
                        )
                        store.saveCredentials(credentials)
                        _uiState.update {
                            it.copy(
                                activeAuthSession = null,
                                authError = null,
                                traktAccount = buildTraktAccountState(credentials)
                            )
                        }
                        postToast("Connected to Trakt as ${credentials.traktUsername}!", SwipeAction.WATCHED)
                        syncNow(silent = false)
                        return@launch
                    }
                }
            }
            _uiState.update { it.copy(activeAuthSession = null, authError = "Activation code timed out") }
        }
    }

    private fun pollSimklSession(session: DeviceAuthSession) {
        authPollJob = viewModelScope.launch {
            var intervalMs = (session.intervalSec.coerceAtLeast(5)) * 1000L
            val deadline = session.startedAtMs + session.expiresInSec * 1000L
            while (System.currentTimeMillis() < deadline) {
                delay(intervalMs)
                when (val poll = simklApi.pollDeviceToken(credentials.simklClientId, session)) {
                    is PollTokenResult.Pending -> Unit
                    is PollTokenResult.SlowDown -> intervalMs += 5000L
                    is PollTokenResult.ExpiredOrDenied -> {
                        _uiState.update { it.copy(activeAuthSession = null, authError = poll.reason) }
                        return@launch
                    }
                    is PollTokenResult.Authorized -> {
                        val profile = simklApi.fetchUserProfile(credentials.simklClientId, poll.accessToken).getOrNull()
                        credentials = credentials.copy(
                            simklAccessToken = poll.accessToken,
                            simklRefreshToken = poll.refreshToken,
                            simklExpiresAtMs = System.currentTimeMillis() + poll.expiresInSec * 1000L,
                            simklUsername = profile?.username ?: "Connected",
                            simklAvatarUrl = profile?.avatarUrl
                        )
                        store.saveCredentials(credentials)
                        _uiState.update {
                            it.copy(
                                activeAuthSession = null,
                                authError = null,
                                simklAccount = buildSimklAccountState(credentials)
                            )
                        }
                        postToast("Connected to Simkl as ${credentials.simklUsername}!", SwipeAction.WATCHED)
                        syncNow(silent = false)
                        return@launch
                    }
                }
            }
            _uiState.update { it.copy(activeAuthSession = null, authError = "Simkl PIN timed out") }
        }
    }

    // ── Sync Engine (Queue + Bidirectional Merge) ──

    private fun enqueueForActiveProviders(item: MediaItem, opType: SyncOpType, rating: Int? = null) {
        val mode = _uiState.value.settings.syncTargetMode
        val useTrakt = mode != SyncTargetMode.SIMKL_ONLY && credentials.traktAccessToken.isNotBlank()
        val useSimkl = mode != SyncTargetMode.TRAKT_ONLY && credentials.simklAccessToken.isNotBlank()

        synchronized(pendingOps) {
            if (useTrakt) {
                pendingOps.add(
                    PendingSyncOp(
                        opId = UUID.randomUUID().toString(),
                        provider = SyncProvider.TRAKT,
                        opType = opType,
                        item = item,
                        rating = rating
                    )
                )
            }
            if (useSimkl) {
                pendingOps.add(
                    PendingSyncOp(
                        opId = UUID.randomUUID().toString(),
                        provider = SyncProvider.SIMKL,
                        opType = opType,
                        item = item,
                        rating = rating
                    )
                )
            }
            store.savePendingSyncOps(pendingOps.toList())
            _uiState.update { it.copy(pendingSyncCount = pendingOps.size) }
        }

        if (useTrakt || useSimkl) {
            viewModelScope.launch { flushPendingOps() }
        }
    }

    private suspend fun ensureFreshTokens() {
        val now = System.currentTimeMillis()
        if (credentials.traktAccessToken.isNotBlank() &&
            credentials.traktRefreshToken.isNotBlank() &&
            credentials.traktExpiresAtMs in 1..(now + 3600_000L)
        ) {
            traktApi.refreshAccessToken(
                credentials.traktClientId,
                credentials.traktClientSecret,
                credentials.traktRefreshToken
            )?.let { refreshed ->
                credentials = credentials.copy(
                    traktAccessToken = refreshed.accessToken,
                    traktRefreshToken = refreshed.refreshToken,
                    traktExpiresAtMs = now + refreshed.expiresInSec * 1000L
                )
                store.saveCredentials(credentials)
            }
        }
        if (credentials.simklAccessToken.isNotBlank() &&
            credentials.simklRefreshToken.isNotBlank() &&
            credentials.simklExpiresAtMs in 1..(now + 3600_000L)
        ) {
            simklApi.refreshAccessToken(
                credentials.simklClientId,
                credentials.simklRefreshToken
            )?.let { refreshed ->
                credentials = credentials.copy(
                    simklAccessToken = refreshed.accessToken,
                    simklRefreshToken = refreshed.refreshToken,
                    simklExpiresAtMs = now + refreshed.expiresInSec * 1000L
                )
                store.saveCredentials(credentials)
            }
        }
    }

    private suspend fun flushPendingOps() {
        ensureFreshTokens()
        val snapshot = synchronized(pendingOps) { pendingOps.toList() }
        if (snapshot.isEmpty()) return

        val succeededIds = HashSet<String>()
        val syncedTraktItemIds = HashSet<String>()
        val syncedSimklItemIds = HashSet<String>()

        for (op in snapshot) {
            val ok = when (op.provider) {
                SyncProvider.TRAKT -> {
                    if (credentials.traktAccessToken.isBlank()) false
                    else traktApi.executeSyncOp(credentials.traktClientId, credentials.traktAccessToken, op)
                }
                SyncProvider.SIMKL -> {
                    if (credentials.simklAccessToken.isBlank()) false
                    else simklApi.executeSyncOp(credentials.simklClientId, credentials.simklAccessToken, op)
                }
            }
            if (ok) {
                succeededIds.add(op.opId)
                if (op.provider == SyncProvider.TRAKT) syncedTraktItemIds.add(op.item.id)
                if (op.provider == SyncProvider.SIMKL) syncedSimklItemIds.add(op.item.id)
            }
        }

        if (succeededIds.isNotEmpty()) {
            synchronized(pendingOps) {
                pendingOps.removeAll { it.opId in succeededIds }
                store.savePendingSyncOps(pendingOps.toList())
            }
            _uiState.update { cur ->
                val updatedMap = cur.trackedMap.mapValues { (id, entry) ->
                    entry.copy(
                        syncedTrakt = entry.syncedTrakt || id in syncedTraktItemIds,
                        syncedSimkl = entry.syncedSimkl || id in syncedSimklItemIds
                    )
                }
                store.saveTrackedEntries(updatedMap)
                cur.copy(
                    trackedMap = updatedMap,
                    pendingSyncCount = pendingOps.size
                )
            }
        }
    }

    fun syncNow(silent: Boolean = false) {
        if (_uiState.value.isSyncingNow) return
        viewModelScope.launch {
            _uiState.update { it.copy(isSyncingNow = true) }
            flushPendingOps()

            var mergedMap = _uiState.value.trackedMap.toMutableMap()
            var pulledCount = 0

            if (credentials.traktAccessToken.isNotBlank() && credentials.traktClientId.isNotBlank()) {
                traktApi.fetchRemoteLibrary(credentials.traktClientId, credentials.traktAccessToken)
                    .onSuccess { remoteList ->
                        pulledCount += mergeRemoteEntries(mergedMap, remoteList, fromTrakt = true)
                        credentials = credentials.copy(traktLastSyncMs = System.currentTimeMillis())
                        store.saveCredentials(credentials)
                    }
            }

            if (credentials.simklAccessToken.isNotBlank() && credentials.simklClientId.isNotBlank()) {
                simklApi.fetchRemoteLibrary(credentials.simklClientId, credentials.simklAccessToken)
                    .onSuccess { remoteList ->
                        pulledCount += mergeRemoteEntries(mergedMap, remoteList, fromTrakt = false)
                        credentials = credentials.copy(simklLastSyncMs = System.currentTimeMillis())
                        store.saveCredentials(credentials)
                    }
            }

            store.saveTrackedEntries(mergedMap)
            var needsReplenish = false
            _uiState.update { cur ->
                val filtered = computeFilteredDeck(cur.rawCatalog, mergedMap, cur.settings.hideAlreadyTracked)
                val canFetchMore = !catalogExhausted && cur.searchQuery.isBlank()
                needsReplenish = canFetchMore && filtered.size <= DeckFilterEngine.PREFETCH_TRIGGER_CARDS
                // Keep currently visible cards on screen briefly while fetching replacement untracked pages
                // so a background sync never rips away visible posters into "End of this reel"
                val nextDeck = if (filtered.isEmpty() && canFetchMore && cur.deckCards.isNotEmpty()) {
                    cur.deckCards.take(3)
                } else {
                    filtered
                }
                cur.copy(
                    isSyncingNow = false,
                    trackedMap = mergedMap,
                    deckCards = nextDeck,
                    isFetchingMore = cur.isFetchingMore || needsReplenish,
                    traktAccount = buildTraktAccountState(credentials),
                    simklAccount = buildSimklAccountState(credentials),
                    pendingSyncCount = pendingOps.size
                )
            }
            if (!silent) {
                postToast("Sync complete · ${mergedMap.size} total shows tracked", SwipeAction.WATCHED)
            }
            if (needsReplenish) {
                deckFetchJob?.join()
                val afterJoin = _uiState.value
                if (!catalogExhausted && afterJoin.searchQuery.isBlank()) {
                    val trueFiltered = computeFilteredDeck(
                        afterJoin.rawCatalog,
                        afterJoin.trackedMap,
                        afterJoin.settings.hideAlreadyTracked
                    )
                    if (trueFiltered.size <= DeckFilterEngine.PREFETCH_TRIGGER_CARDS) {
                        val gen = deckGeneration
                        _uiState.update { it.copy(isFetchingMore = true) }
                        deckFetchJob = viewModelScope.launch {
                            fetchUntilBuffered(gen = gen, isInitialReset = false)
                        }
                    }
                }
            }
        }
    }

    private fun mergeRemoteEntries(
        target: MutableMap<String, TrackedEntry>,
        remoteList: List<TrackedEntry>,
        fromTrakt: Boolean
    ): Int {
        var addedOrUpdated = 0
        for (remote in remoteList) {
            val existing = findMatchingTracked(remote.item, target)
            if (existing == null) {
                target[remote.item.id] = remote
                addedOrUpdated++
            } else {
                // Keep WATCHED over TO_WATCH if watched on either platform
                val resolvedAction = if (existing.action == SwipeAction.WATCHED || remote.action == SwipeAction.WATCHED) {
                    SwipeAction.WATCHED
                } else {
                    remote.action
                }
                val mergedItem = existing.item.copy(
                    posterUrl = existing.item.posterUrl.ifBlank { remote.item.posterUrl },
                    backdropUrl = existing.item.backdropUrl.ifBlank { remote.item.backdropUrl },
                    logoUrl = existing.item.logoUrl ?: remote.item.logoUrl,
                    overview = existing.item.overview.ifBlank { remote.item.overview },
                    imdbRating = existing.item.imdbRating.ifBlank { remote.item.imdbRating },
                    ids = existing.item.ids.mergeWith(remote.item.ids)
                )
                target[existing.item.id] = existing.copy(
                    item = mergedItem,
                    action = resolvedAction,
                    userRating = existing.userRating ?: remote.userRating,
                    syncedTrakt = existing.syncedTrakt || fromTrakt,
                    syncedSimkl = existing.syncedSimkl || !fromTrakt
                )
            }
        }
        return addedOrUpdated
    }

    private fun findMatchingTracked(item: MediaItem, map: Map<String, TrackedEntry>): TrackedEntry? =
        DeckFilterEngine.findMatchingTracked(item, map)

    private fun computeFilteredDeck(
        raw: List<MediaItem>,
        tracked: Map<String, TrackedEntry>,
        hideTracked: Boolean
    ): List<MediaItem> = DeckFilterEngine.computeFilteredDeck(raw, tracked, hideTracked)

    private fun buildTraktAccountState(c: StoredCredentials): ProviderAccountState = ProviderAccountState(
        provider = SyncProvider.TRAKT,
        clientIdConfigured = c.traktClientId.isNotBlank(),
        clientSecretConfigured = c.traktClientSecret.isNotBlank(),
        isConnected = c.traktAccessToken.isNotBlank(),
        username = c.traktUsername,
        avatarUrl = c.traktAvatarUrl,
        lastSyncMs = c.traktLastSyncMs,
        statusMessage = when {
            c.traktAccessToken.isNotBlank() -> "Signed in as @${c.traktUsername.ifBlank { "user" }}"
            c.traktClientId.isNotBlank() && c.traktClientSecret.isNotBlank() -> "Client keys saved, ready to sign in"
            else -> "Paste your Trakt client ID and secret via QR"
        }
    )

    private fun buildSimklAccountState(c: StoredCredentials): ProviderAccountState = ProviderAccountState(
        provider = SyncProvider.SIMKL,
        clientIdConfigured = c.simklClientId.isNotBlank(),
        clientSecretConfigured = true,
        isConnected = c.simklAccessToken.isNotBlank(),
        username = c.simklUsername,
        avatarUrl = c.simklAvatarUrl,
        lastSyncMs = c.simklLastSyncMs,
        statusMessage = when {
            c.simklAccessToken.isNotBlank() -> "Signed in as ${c.simklUsername.ifBlank { "Simkl user" }}"
            c.simklClientId.isNotBlank() -> "Client ID saved, ready to sign in"
            else -> "Paste your Simkl client ID via QR"
        }
    )

    private fun postToast(message: String, accent: SwipeAction? = null) {
        toastClearJob?.cancel()
        val banner = UiBannerToast(text = message, accentType = accent)
        _uiState.update { it.copy(toast = banner) }
        toastClearJob = viewModelScope.launch {
            delay(2600L)
            _uiState.update { cur -> if (cur.toast?.id == banner.id) cur.copy(toast = null) else cur }
        }
    }

    override fun onCleared() {
        companionServer.stop()
        super.onCleared()
    }
}

object DeckFilterEngine {
    const val MIN_BUFFER_CARDS = 16
    const val PREFETCH_TRIGGER_CARDS = 12
    const val MAX_PAGES_PER_BURST = 8
    const val MAX_PAGES_WHEN_EMPTY = 18

    fun findMatchingTracked(item: MediaItem, map: Map<String, TrackedEntry>): TrackedEntry? {
        map[item.id]?.let { return it }
        val imdb = item.ids.imdb?.takeIf { it.isNotBlank() }
        val simkl = item.ids.simkl?.takeIf { it > 0 }
        val trakt = item.ids.trakt?.takeIf { it > 0 }
        val tmdb = item.ids.tmdb?.takeIf { it > 0 }
        val mal = item.ids.mal?.takeIf { it > 0 }

        return map.values.firstOrNull { entry ->
            val eIds = entry.item.ids
            (imdb != null && eIds.imdb == imdb) ||
                (simkl != null && eIds.simkl == simkl) ||
                (trakt != null && eIds.trakt == trakt) ||
                (tmdb != null && eIds.tmdb == tmdb && entry.item.mediaType == item.mediaType) ||
                (mal != null && eIds.mal == mal)
        }
    }

    fun computeFilteredDeck(
        raw: List<MediaItem>,
        tracked: Map<String, TrackedEntry>,
        hideTracked: Boolean
    ): List<MediaItem> {
        if (!hideTracked) return raw
        return raw.filter { candidate -> findMatchingTracked(candidate, tracked) == null }
    }

    data class ReplenishResult(
        val rawCatalog: List<MediaItem>,
        val visibleDeck: List<MediaItem>,
        val nextPage: Int,
        val exhausted: Boolean
    )

    suspend fun replenishFromPages(
        initialRaw: List<MediaItem>,
        tracked: Map<String, TrackedEntry>,
        hideTracked: Boolean,
        startPage: Int,
        fetchPage: suspend (Int) -> List<MediaItem>
    ): ReplenishResult {
        var raw = initialRaw
        var page = startPage
        var exhausted = false
        var pagesFetched = 0
        var consecutiveDuplicates = 0

        while (!exhausted) {
            val currentVisible = computeFilteredDeck(raw, tracked, hideTracked)
            if (currentVisible.size >= MIN_BUFFER_CARDS) break
            val maxBurst = if (currentVisible.isEmpty()) MAX_PAGES_WHEN_EMPTY else MAX_PAGES_PER_BURST
            if (pagesFetched >= maxBurst) break

            val batch = fetchPage(page)
            page++
            pagesFetched++

            val existingIds = raw.asSequence().map { it.id }.toHashSet()
            val newItems = batch.filter { it.id !in existingIds }

            if (batch.isEmpty()) {
                exhausted = true
            } else if (newItems.isEmpty()) {
                consecutiveDuplicates++
                if (consecutiveDuplicates >= 2) exhausted = true
            } else {
                consecutiveDuplicates = 0
            }

            raw = raw + newItems
            if (computeFilteredDeck(raw, tracked, hideTracked).size >= MIN_BUFFER_CARDS) break
        }

        return ReplenishResult(
            rawCatalog = raw,
            visibleDeck = computeFilteredDeck(raw, tracked, hideTracked),
            nextPage = page,
            exhausted = exhausted
        )
    }
}
