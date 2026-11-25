package com.burbsklopek.flickdeck.data.remote

import com.burbsklopek.flickdeck.model.*
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.time.Instant

sealed class PollTokenResult {
    data object Pending : PollTokenResult()
    data object SlowDown : PollTokenResult()
    data class ExpiredOrDenied(val reason: String) : PollTokenResult()
    data class Authorized(
        val accessToken: String,
        val refreshToken: String,
        val expiresInSec: Long
    ) : PollTokenResult()
}

data class UserProfileInfo(
    val username: String,
    val avatarUrl: String?
)

class TraktApiClient {
    private val jsonMedia = "application/json; charset=utf-8".toMediaType()
    private val baseUrl = "https://api.trakt.tv"

    private fun Request.Builder.traktHeaders(clientId: String, accessToken: String? = null): Request.Builder {
        header("Content-Type", "application/json")
        header("trakt-api-version", "2")
        header("trakt-api-key", clientId.trim())
        if (!accessToken.isNullOrBlank()) {
            header("Authorization", "Bearer ${accessToken.trim()}")
        }
        return this
    }

    suspend fun requestDeviceCode(clientId: String): Result<DeviceAuthSession> = withContext(Dispatchers.IO) {
        runCatching {
            val payload = JsonObject().apply { addProperty("client_id", clientId.trim()) }
            val req = Request.Builder()
                .url("$baseUrl/oauth/device/code")
                .traktHeaders(clientId)
                .post(payload.toString().toRequestBody(jsonMedia))
                .build()

            HttpProvider.client.newCall(req).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    error("Trakt device code error (${resp.code}): ${body.take(140)}")
                }
                val obj = JsonParser.parseString(body).asJsonObject
                DeviceAuthSession(
                    provider = SyncProvider.TRAKT,
                    userCode = obj.get("user_code")?.asString ?: error("Missing user_code"),
                    deviceCode = obj.get("device_code")?.asString ?: error("Missing device_code"),
                    verificationUrl = obj.get("verification_url")?.asString ?: "https://trakt.tv/activate",
                    expiresInSec = obj.get("expires_in")?.asInt ?: 600,
                    intervalSec = obj.get("interval")?.asInt ?: 5
                )
            }
        }
    }

    suspend fun pollDeviceToken(
        clientId: String,
        clientSecret: String,
        deviceCode: String
    ): PollTokenResult = withContext(Dispatchers.IO) {
        runCatching {
            val payload = JsonObject().apply {
                addProperty("code", deviceCode)
                addProperty("client_id", clientId.trim())
                addProperty("client_secret", clientSecret.trim())
            }
            val req = Request.Builder()
                .url("$baseUrl/oauth/device/token")
                .traktHeaders(clientId)
                .post(payload.toString().toRequestBody(jsonMedia))
                .build()

            HttpProvider.client.newCall(req).execute().use { resp ->
                when (resp.code) {
                    200 -> {
                        val obj = JsonParser.parseString(resp.body?.string().orEmpty()).asJsonObject
                        PollTokenResult.Authorized(
                            accessToken = obj.get("access_token")?.asString.orEmpty(),
                            refreshToken = obj.get("refresh_token")?.asString.orEmpty(),
                            expiresInSec = obj.get("expires_in")?.asLong ?: 7776000L
                        )
                    }
                    400 -> PollTokenResult.Pending
                    429 -> PollTokenResult.SlowDown
                    404 -> PollTokenResult.ExpiredOrDenied("Invalid device code")
                    409 -> PollTokenResult.ExpiredOrDenied("Code already used")
                    410 -> PollTokenResult.ExpiredOrDenied("Activation code expired")
                    418 -> PollTokenResult.ExpiredOrDenied("Authorization denied by user")
                    else -> PollTokenResult.ExpiredOrDenied("HTTP ${resp.code}")
                }
            }
        }.getOrElse { PollTokenResult.Pending }
    }

    suspend fun refreshAccessToken(
        clientId: String,
        clientSecret: String,
        refreshToken: String
    ): PollTokenResult.Authorized? = withContext(Dispatchers.IO) {
        runCatching {
            val payload = JsonObject().apply {
                addProperty("refresh_token", refreshToken)
                addProperty("client_id", clientId.trim())
                addProperty("client_secret", clientSecret.trim())
                addProperty("redirect_uri", "urn:ietf:wg:oauth:2.0:oob")
                addProperty("grant_type", "refresh_token")
            }
            val req = Request.Builder()
                .url("$baseUrl/oauth/token")
                .traktHeaders(clientId)
                .post(payload.toString().toRequestBody(jsonMedia))
                .build()
            HttpProvider.client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@use null
                val obj = JsonParser.parseString(resp.body?.string().orEmpty()).asJsonObject
                PollTokenResult.Authorized(
                    accessToken = obj.get("access_token")?.asString.orEmpty(),
                    refreshToken = obj.get("refresh_token")?.asString ?: refreshToken,
                    expiresInSec = obj.get("expires_in")?.asLong ?: 7776000L
                )
            }
        }.getOrNull()
    }

    suspend fun fetchUserProfile(clientId: String, accessToken: String): Result<UserProfileInfo> =
        withContext(Dispatchers.IO) {
            runCatching {
                val req = Request.Builder()
                    .url("$baseUrl/users/settings")
                    .traktHeaders(clientId, accessToken)
                    .get()
                    .build()
                HttpProvider.client.newCall(req).execute().use { resp ->
                    val body = resp.body?.string().orEmpty()
                    if (!resp.isSuccessful) error("Trakt profile HTTP ${resp.code}")
                    val root = JsonParser.parseString(body).asJsonObject
                    val user = root.optObj("user")
                    val username = user?.optStr("username") ?: "Trakt User"
                    val avatar = user?.optObj("images")
                        ?.optObj("avatar")
                        ?.optStr("full")
                    UserProfileInfo(username = username, avatarUrl = avatar)
                }
            }
        }

    suspend fun fetchRemoteLibrary(clientId: String, accessToken: String): Result<List<TrackedEntry>> =
        withContext(Dispatchers.IO) {
            runCatching {
                val results = LinkedHashMap<String, TrackedEntry>()

                // Pull Watchlist first, then Watched (so Watched wins if in both)
                fetchSyncList(clientId, accessToken, "/sync/watchlist/shows", MediaType.SHOW, SwipeAction.TO_WATCH)
                    .forEach { results[it.item.id] = it }
                fetchSyncList(clientId, accessToken, "/sync/watchlist/movies", MediaType.MOVIE, SwipeAction.TO_WATCH)
                    .forEach { results[it.item.id] = it }
                fetchSyncList(clientId, accessToken, "/sync/watched/shows", MediaType.SHOW, SwipeAction.WATCHED)
                    .forEach { results[it.item.id] = it }
                fetchSyncList(clientId, accessToken, "/sync/watched/movies", MediaType.MOVIE, SwipeAction.WATCHED)
                    .forEach { results[it.item.id] = it }

                results.values.toList()
            }
        }

    private fun fetchSyncList(
        clientId: String,
        accessToken: String,
        endpoint: String,
        mediaType: MediaType,
        action: SwipeAction
    ): List<TrackedEntry> {
        val req = Request.Builder()
            .url("$baseUrl$endpoint")
            .traktHeaders(clientId, accessToken)
            .get()
            .build()
        HttpProvider.client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return emptyList()
            val arr = runCatching { JsonParser.parseString(resp.body?.string().orEmpty()).takeIf { it.isJsonArray }?.asJsonArray }
                .getOrNull() ?: return emptyList()
            return arr.mapNotNull { elem ->
                runCatching {
                    val obj = elem.takeIf { it.isJsonObject }?.asJsonObject ?: return@runCatching null
                    val mediaObj = obj.optObj("show") ?: obj.optObj("movie") ?: return@runCatching null
                    val item = parseTraktMediaObject(mediaObj, mediaType) ?: return@runCatching null
                    TrackedEntry(
                        item = item,
                        action = action,
                        syncedTrakt = true
                    )
                }.getOrNull()
            }
        }
    }

    suspend fun executeSyncOp(
        clientId: String,
        accessToken: String,
        op: PendingSyncOp
    ): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val endpoint = when (op.opType) {
                SyncOpType.ADD_WATCHED -> "/sync/history"
                SyncOpType.ADD_WATCHLIST -> "/sync/watchlist"
                SyncOpType.REMOVE_WATCHED -> "/sync/history/remove"
                SyncOpType.REMOVE_WATCHLIST -> "/sync/watchlist/remove"
                SyncOpType.RATE -> "/sync/ratings"
            }

            val itemNode = JsonObject().apply {
                addProperty("title", op.item.title)
                op.item.year.take(4).toIntOrNull()?.let { addProperty("year", it) }
                if (op.opType == SyncOpType.ADD_WATCHED) {
                    addProperty("watched_at", Instant.now().toString())
                }
                if (op.opType == SyncOpType.RATE && op.rating != null) {
                    addProperty("rating", op.rating)
                }
                add("ids", JsonObject().apply {
                    op.item.ids.trakt?.let { addProperty("trakt", it) }
                    op.item.ids.slug?.let { addProperty("slug", it) }
                    op.item.ids.imdb?.let { addProperty("imdb", it) }
                    op.item.ids.tmdb?.let { addProperty("tmdb", it) }
                    op.item.ids.tvdb?.let { addProperty("tvdb", it) }
                })
            }

            val bucketKey = if (op.item.mediaType == MediaType.MOVIE) "movies" else "shows"
            val root = JsonObject().apply {
                add(bucketKey, JsonArray().apply { add(itemNode) })
            }

            val req = Request.Builder()
                .url("$baseUrl$endpoint")
                .traktHeaders(clientId, accessToken)
                .post(root.toString().toRequestBody(jsonMedia))
                .build()

            HttpProvider.client.newCall(req).execute().use { resp ->
                resp.isSuccessful
            }
        }.getOrDefault(false)
    }

    suspend fun fetchDiscovery(
        clientId: String,
        accessToken: String?,
        category: FeedCategory,
        mediaType: MediaType
    ): List<MediaItem> = withContext(Dispatchers.IO) {
        if (clientId.isBlank()) return@withContext emptyList()
        runCatching {
            val typePath = if (mediaType == MediaType.MOVIE) "movies" else "shows"
            val genreParam = if (mediaType == MediaType.ANIME) "&genres=anime" else ""
            val path = when {
                category == FeedCategory.FOR_YOU && !accessToken.isNullOrBlank() ->
                    "/$typePath/recommendationsFallback".let { "/recommendations/$typePath?extended=full&limit=35$genreParam" }
                category == FeedCategory.TRENDING ->
                    "/$typePath/trending?extended=full&limit=35$genreParam"
                category == FeedCategory.TOP_RATED ->
                    "/$typePath/popular?extended=full&limit=35$genreParam"
                category == FeedCategory.NEW_RELEASES ->
                    "/$typePath/anticipated?extended=full&limit=35$genreParam"
                else ->
                    "/$typePath/trending?extended=full&limit=35$genreParam"
            }

            val req = Request.Builder()
                .url("$baseUrl$path")
                .traktHeaders(clientId, accessToken)
                .get()
                .build()

            HttpProvider.client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@use emptyList()
                val arr = JsonParser.parseString(resp.body?.string().orEmpty())
                    .takeIf { it.isJsonArray }?.asJsonArray ?: return@use emptyList()
                arr.mapNotNull { elem ->
                    runCatching {
                        val obj = elem.takeIf { it.isJsonObject }?.asJsonObject ?: return@runCatching null
                        val inner = obj.optObj("show") ?: obj.optObj("movie") ?: obj
                        parseTraktMediaObject(inner, mediaType)
                    }.getOrNull()
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun parseTraktMediaObject(obj: JsonObject, mediaType: MediaType): MediaItem? {
        val title = obj.optStr("title") ?: return null
        val year = obj.get("year")?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()
        val idsObj = obj.optObj("ids")
        val imdb = idsObj?.optStr("imdb")
        val trakt = idsObj?.get("trakt")?.takeIf { it.isJsonPrimitive }?.let { runCatching { it.asInt }.getOrNull() }
        val tmdb = idsObj?.get("tmdb")?.takeIf { it.isJsonPrimitive }?.let { runCatching { it.asInt }.getOrNull() }
        val tvdb = idsObj?.get("tvdb")?.takeIf { it.isJsonPrimitive }?.let { runCatching { it.asInt }.getOrNull() }
        val slug = idsObj?.optStr("slug")

        val extIds = ExternalIds(imdb = imdb, trakt = trakt, tmdb = tmdb, tvdb = tvdb, slug = slug)
        val ratingDouble = obj.get("rating")?.takeIf { it.isJsonPrimitive }?.let { runCatching { it.asDouble }.getOrNull() }
        val ratingStr = if (ratingDouble != null && ratingDouble > 0.0) String.format("%.1f", ratingDouble) else ""
        val overview = obj.optStr("overview").orEmpty()
        val runtimeInt = obj.get("runtime")?.takeIf { it.isJsonPrimitive }?.let { runCatching { it.asInt }.getOrNull() }
        val network = obj.optStr("network")
        val status = obj.optStr("status")
            ?.replaceFirstChar { it.uppercase() }.orEmpty()
        val trailerUrl = obj.optStr("trailer")
        val youtubeId = trailerUrl?.let { extractYoutubeId(it) }
        val genres = obj.optArr("genres")?.mapNotNull { g ->
            g.takeIf { it.isJsonPrimitive }?.asString?.replaceFirstChar { it.uppercase() }
        } ?: emptyList()

        return MediaItem(
            id = extIds.canonicalKey(title, year),
            mediaType = mediaType,
            title = title,
            year = year,
            status = status,
            imdbRating = ratingStr,
            runtime = if (runtimeInt != null && runtimeInt > 0) "${runtimeInt}m" else "",
            genres = genres,
            overview = overview,
            network = network,
            posterUrl = HttpProvider.metahubPoster(imdb),
            backdropUrl = HttpProvider.metahubBackdrop(imdb),
            logoUrl = HttpProvider.metahubLogo(imdb),
            trailerYoutubeId = youtubeId,
            ids = extIds,
            sourceBadge = "Trakt"
        )
    }

    private fun extractYoutubeId(url: String): String? {
        val m1 = Regex("[?&]v=([A-Za-z0-9_-]{11})").find(url)
        if (m1 != null) return m1.groupValues[1]
        val m2 = Regex("youtu\\.be/([A-Za-z0-9_-]{11})").find(url)
        return m2?.groupValues?.get(1)
    }
}
