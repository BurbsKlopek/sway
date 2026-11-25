package com.burbsklopek.flickdeck.model

enum class MediaType(val label: String) {
    SHOW("TV Shows"),
    ANIME("Anime"),
    MOVIE("Movies")
}

enum class FeedCategory(val label: String) {
    TRENDING("Trending"),
    TOP_RATED("Top Rated"),
    NEW_RELEASES("Fresh & New"),
    FOR_YOU("For You")
}

enum class SwipeAction(val label: String) {
    TO_WATCH("To Watch"),
    WATCHED("Watched"),
    SKIPPED("Passed")
}

enum class SyncProvider(val displayName: String) {
    TRAKT("Trakt"),
    SIMKL("Simkl")
}

enum class SyncOpType {
    ADD_WATCHED,
    ADD_WATCHLIST,
    REMOVE_WATCHED,
    REMOVE_WATCHLIST,
    RATE
}

data class ExternalIds(
    val imdb: String? = null,
    val tmdb: Int? = null,
    val tvdb: Int? = null,
    val trakt: Int? = null,
    val simkl: Int? = null,
    val mal: Int? = null,
    val slug: String? = null
) {
    fun canonicalKey(fallbackTitle: String, year: String): String {
        imdb?.takeIf { it.isNotBlank() }?.let { return "imdb:$it" }
        simkl?.takeIf { it > 0 }?.let { return "simkl:$it" }
        trakt?.takeIf { it > 0 }?.let { return "trakt:$it" }
        tmdb?.takeIf { it > 0 }?.let { return "tmdb:$it" }
        tvdb?.takeIf { it > 0 }?.let { return "tvdb:$it" }
        mal?.takeIf { it > 0 }?.let { return "mal:$it" }
        slug?.takeIf { it.isNotBlank() }?.let { return "slug:$it" }
        val normalized = fallbackTitle.lowercase().replace(Regex("[^a-z0-9]+"), "-")
        return "title:$normalized-$year"
    }

    fun mergeWith(other: ExternalIds): ExternalIds = ExternalIds(
        imdb = imdb ?: other.imdb,
        tmdb = tmdb ?: other.tmdb,
        tvdb = tvdb ?: other.tvdb,
        trakt = trakt ?: other.trakt,
        simkl = simkl ?: other.simkl,
        mal = mal ?: other.mal,
        slug = slug ?: other.slug
    )
}

data class MediaItem(
    val id: String,
    val mediaType: MediaType = MediaType.SHOW,
    val title: String,
    val year: String = "",
    val status: String = "",
    val imdbRating: String = "",
    val runtime: String = "",
    val genres: List<String> = emptyList(),
    val overview: String = "",
    val cast: List<String> = emptyList(),
    val creators: List<String> = emptyList(),
    val awards: String? = null,
    val network: String? = null,
    val seasonsCount: Int? = null,
    val posterUrl: String = "",
    val backdropUrl: String = "",
    val logoUrl: String? = null,
    val trailerYoutubeId: String? = null,
    val ids: ExternalIds = ExternalIds(),
    val sourceBadge: String = "Cinemeta"
)

data class TrackedEntry(
    val item: MediaItem,
    val action: SwipeAction,
    val updatedAtMs: Long = System.currentTimeMillis(),
    val userRating: Int? = null,
    val syncedTrakt: Boolean = false,
    val syncedSimkl: Boolean = false
)

data class SwipeHistoryStep(
    val item: MediaItem,
    val appliedAction: SwipeAction,
    val previousEntry: TrackedEntry?,
    val timestampMs: Long = System.currentTimeMillis()
)

data class PendingSyncOp(
    val opId: String,
    val provider: SyncProvider,
    val opType: SyncOpType,
    val item: MediaItem,
    val rating: Int? = null,
    val createdAtMs: Long = System.currentTimeMillis(),
    val attempts: Int = 0
)

data class DeviceAuthSession(
    val provider: SyncProvider,
    val userCode: String,
    val deviceCode: String,
    val verificationUrl: String,
    val expiresInSec: Int,
    val intervalSec: Int,
    val startedAtMs: Long = System.currentTimeMillis(),
    val isLegacyPin: Boolean = false
)

data class ProviderAccountState(
    val provider: SyncProvider,
    val clientIdConfigured: Boolean = false,
    val clientSecretConfigured: Boolean = false,
    val isConnected: Boolean = false,
    val username: String = "",
    val avatarUrl: String? = null,
    val lastSyncMs: Long = 0L,
    val statusMessage: String = "Not connected"
)
