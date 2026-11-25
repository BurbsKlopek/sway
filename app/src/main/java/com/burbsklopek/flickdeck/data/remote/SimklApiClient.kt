package com.burbsklopek.flickdeck.data.remote

import com.burbsklopek.flickdeck.BuildConfig
import com.burbsklopek.flickdeck.model.*
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

class SimklApiClient {
    private val jsonMedia = "application/json; charset=utf-8".toMediaType()
    private val baseUrl = "https://api.simkl.com"

    private fun Request.Builder.simklHeaders(clientId: String, accessToken: String? = null): Request.Builder {
        header("simkl-api-key", clientId.trim())
        header("Content-Type", "application/json")
        if (!accessToken.isNullOrBlank()) {
            header("Authorization", "Bearer ${accessToken.trim()}")
        }
        return this
    }

    /**
     * Supports Simkl Auth V2 RFC 8628 (/oauth2/device) with automatic fallback to V1 (/oauth/pin)
     * so both newly created V2 Client IDs and older V1 Client IDs work seamlessly.
     */
    suspend fun requestDeviceCode(clientId: String): Result<DeviceAuthSession> = withContext(Dispatchers.IO) {
        val trimmedId = clientId.trim()
        // 1. Try modern Auth V2 RFC 8628 endpoint first
        val v2Attempt = runCatching {
            val form = FormBody.Builder()
                .add("client_id", trimmedId)
                .add("scope", "media:read media:write")
                .build()
            val req = Request.Builder()
                .url("$baseUrl/oauth2/device")
                .header("simkl-api-key", trimmedId)
                .post(form)
                .build()

            HttpProvider.client.newCall(req).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) error("V2 HTTP ${resp.code}: $body")
                val obj = JsonParser.parseString(body).asJsonObject
                val userCode = obj.get("user_code")?.asString ?: error("Missing user_code")
                val deviceCode = obj.get("device_code")?.asString ?: userCode
                val verifyUrl = obj.get("verification_uri")?.asString
                    ?: obj.get("verification_url")?.asString
                    ?: "https://simkl.com/pin"
                DeviceAuthSession(
                    provider = SyncProvider.SIMKL,
                    userCode = userCode,
                    deviceCode = deviceCode,
                    verificationUrl = verifyUrl,
                    expiresInSec = obj.get("expires_in")?.asInt ?: 900,
                    intervalSec = obj.get("interval")?.asInt ?: 5,
                    isLegacyPin = false
                )
            }
        }
        if (v2Attempt.isSuccess) return@withContext v2Attempt

        // 2. Fallback to Simkl Legacy V1 PIN flow
        runCatching {
            val req = Request.Builder()
                .url("$baseUrl/oauth/pin?client_id=$trimmedId")
                .header("simkl-api-key", trimmedId)
                .get()
                .build()
            HttpProvider.client.newCall(req).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    error("Simkl PIN error (${resp.code}): ${body.take(140)}")
                }
                val obj = JsonParser.parseString(body).asJsonObject
                val userCode = obj.get("user_code")?.asString ?: error("Missing user_code")
                DeviceAuthSession(
                    provider = SyncProvider.SIMKL,
                    userCode = userCode,
                    deviceCode = userCode,
                    verificationUrl = obj.get("verification_url")?.asString ?: "https://simkl.com/pin",
                    expiresInSec = obj.get("expires_in")?.asInt ?: 900,
                    intervalSec = obj.get("interval")?.asInt ?: 5,
                    isLegacyPin = true
                )
            }
        }
    }

    suspend fun pollDeviceToken(
        clientId: String,
        session: DeviceAuthSession
    ): PollTokenResult = withContext(Dispatchers.IO) {
        val trimmedId = clientId.trim()
        if (!session.isLegacyPin) {
            runCatching {
                val form = FormBody.Builder()
                    .add("grant_type", "urn:ietf:params:oauth:grant-type:device_code")
                    .add("client_id", trimmedId)
                    .add("device_code", session.deviceCode)
                    .build()
                val req = Request.Builder()
                    .url("$baseUrl/oauth2/token")
                    .header("simkl-api-key", trimmedId)
                    .post(form)
                    .build()
                HttpProvider.client.newCall(req).execute().use { resp ->
                    val body = resp.body?.string().orEmpty()
                    if (resp.isSuccessful) {
                        val obj = JsonParser.parseString(body).asJsonObject
                        val token = obj.get("access_token")?.asString.orEmpty()
                        if (token.isNotBlank()) {
                            return@use PollTokenResult.Authorized(
                                accessToken = token,
                                refreshToken = obj.get("refresh_token")?.asString.orEmpty(),
                                expiresInSec = obj.get("expires_in")?.asLong ?: 604800L
                            )
                        }
                    }
                    val errCode = runCatching {
                        JsonParser.parseString(body).asJsonObject.get("error")?.asString
                    }.getOrNull()
                    when (errCode) {
                        "authorization_pending", null -> PollTokenResult.Pending
                        "slow_down" -> PollTokenResult.SlowDown
                        "expired_token" -> PollTokenResult.ExpiredOrDenied("Simkl code expired")
                        "access_denied" -> PollTokenResult.ExpiredOrDenied("Authorization denied")
                        else -> PollTokenResult.Pending
                    }
                }
            }.getOrElse { PollTokenResult.Pending }
        } else {
            runCatching {
                val req = Request.Builder()
                    .url("$baseUrl/oauth/pin/${session.userCode}?client_id=$trimmedId")
                    .header("simkl-api-key", trimmedId)
                    .get()
                    .build()
                HttpProvider.client.newCall(req).execute().use { resp ->
                    val body = resp.body?.string().orEmpty()
                    if (!resp.isSuccessful) return@use PollTokenResult.Pending
                    val obj = JsonParser.parseString(body).asJsonObject
                    val result = obj.get("result")?.asString
                    if (result == "OK" && obj.has("access_token")) {
                        PollTokenResult.Authorized(
                            accessToken = obj.get("access_token").asString,
                            refreshToken = "",
                            expiresInSec = 31536000L
                        )
                    } else {
                        PollTokenResult.Pending
                    }
                }
            }.getOrElse { PollTokenResult.Pending }
        }
    }

    suspend fun refreshAccessToken(
        clientId: String,
        refreshToken: String
    ): PollTokenResult.Authorized? = withContext(Dispatchers.IO) {
        if (refreshToken.isBlank()) return@withContext null
        runCatching {
            val form = FormBody.Builder()
                .add("grant_type", "refresh_token")
                .add("client_id", clientId.trim())
                .add("refresh_token", refreshToken.trim())
                .build()
            val req = Request.Builder()
                .url("$baseUrl/oauth2/token")
                .header("simkl-api-key", clientId.trim())
                .post(form)
                .build()
            HttpProvider.client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@use null
                val obj = JsonParser.parseString(resp.body?.string().orEmpty()).asJsonObject
                PollTokenResult.Authorized(
                    accessToken = obj.get("access_token")?.asString.orEmpty(),
                    refreshToken = obj.get("refresh_token")?.asString ?: refreshToken,
                    expiresInSec = obj.get("expires_in")?.asLong ?: 604800L
                )
            }
        }.getOrNull()
    }

    suspend fun fetchUserProfile(clientId: String, accessToken: String): Result<UserProfileInfo> =
        withContext(Dispatchers.IO) {
            runCatching {
                val req = Request.Builder()
                    .url("$baseUrl/users/settings?client_id=${clientId.trim()}")
                    .simklHeaders(clientId, accessToken)
                    .post("{}".toRequestBody(jsonMedia))
                    .build()
                HttpProvider.client.newCall(req).execute().use { resp ->
                    val body = resp.body?.string().orEmpty()
                    if (!resp.isSuccessful) error("Simkl profile HTTP ${resp.code}")
                    val root = JsonParser.parseString(body).asJsonObject
                    val user = root.optObj("user")
                    val name = user?.optStr("name")?.takeIf { it.isNotBlank() }
                        ?: root.optObj("account")?.get("id")?.toString()
                        ?: "Simkl User"
                    val avatar = user?.optStr("avatar")
                    UserProfileInfo(username = name, avatarUrl = avatar)
                }
            }
        }

    suspend fun fetchRemoteLibrary(clientId: String, accessToken: String): Result<List<TrackedEntry>> =
        withContext(Dispatchers.IO) {
            runCatching {
                val map = LinkedHashMap<String, TrackedEntry>()
                fetchSimklBucket(clientId, accessToken, "shows", MediaType.SHOW).forEach { map[it.item.id] = it }
                fetchSimklBucket(clientId, accessToken, "anime", MediaType.ANIME).forEach { map[it.item.id] = it }
                fetchSimklBucket(clientId, accessToken, "movies", MediaType.MOVIE).forEach { map[it.item.id] = it }
                map.values.toList()
            }
        }

    private fun fetchSimklBucket(
        clientId: String,
        accessToken: String,
        bucket: String,
        mediaType: MediaType
    ): List<TrackedEntry> {
        val req = Request.Builder()
            .url("$baseUrl/sync/all-items/$bucket/?client_id=${clientId.trim()}&app-name=Sway&app-version=${BuildConfig.VERSION_NAME}")
            .simklHeaders(clientId, accessToken)
            .post("{}".toRequestBody(jsonMedia))
            .build()
        HttpProvider.client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return emptyList()
            val root = runCatching { JsonParser.parseString(resp.body?.string().orEmpty()).asJsonObject }
                .getOrNull() ?: return emptyList()
            val arr = root.optArr(bucket) ?: return emptyList()
            return arr.mapNotNull { elem ->
                runCatching {
                    val obj = elem.takeIf { it.isJsonObject }?.asJsonObject ?: return@runCatching null
                    val status = obj.optStr("status") ?: return@runCatching null
                    val mappedAction = when (status) {
                        "completed", "watching" -> SwipeAction.WATCHED
                        "plantowatch", "hold" -> SwipeAction.TO_WATCH
                        "dropped", "notinteresting" -> SwipeAction.SKIPPED
                        else -> return@runCatching null
                    }
                    val mediaObj = obj.optObj("show") ?: obj.optObj("movie") ?: return@runCatching null
                    val item = parseSimklMedia(mediaObj, mediaType) ?: return@runCatching null
                    val userRating = obj.get("user_rating")?.takeIf { it.isJsonPrimitive }?.let { runCatching { it.asInt }.getOrNull() }
                    TrackedEntry(
                        item = item,
                        action = mappedAction,
                        userRating = userRating,
                        syncedSimkl = true
                    )
                }.getOrNull()
            }
        }
    }

    fun buildSyncRequestPayload(op: PendingSyncOp): Pair<String, JsonObject> {
        val bucketKey = when (op.item.mediaType) {
            MediaType.MOVIE -> "movies"
            MediaType.ANIME -> "anime"
            MediaType.SHOW -> "shows"
        }

        val idsJson = JsonObject().apply {
            op.item.ids.simkl?.let { addProperty("simkl", it) }
            op.item.ids.imdb?.let { addProperty("imdb", it) }
            op.item.ids.tmdb?.let { addProperty("tmdb", it) }
            op.item.ids.tvdb?.let { addProperty("tvdb", it) }
            op.item.ids.mal?.let { addProperty("mal", it) }
        }

        return when (op.opType) {
            SyncOpType.ADD_WATCHED, SyncOpType.ADD_WATCHLIST -> {
                val targetStatus = if (op.opType == SyncOpType.ADD_WATCHED) "completed" else "plantowatch"
                val entry = JsonObject().apply {
                    addProperty("to", targetStatus)
                    addProperty("title", op.item.title)
                    op.item.year.take(4).toIntOrNull()?.let { addProperty("year", it) }
                    add("ids", idsJson)
                }
                val root = JsonObject().apply {
                    add(bucketKey, JsonArray().apply { add(entry) })
                }
                "/sync/add-to-list" to root
            }
            SyncOpType.REMOVE_WATCHED, SyncOpType.REMOVE_WATCHLIST -> {
                val entry = JsonObject().apply {
                    addProperty("title", op.item.title)
                    add("ids", idsJson)
                }
                val root = JsonObject().apply {
                    add(bucketKey, JsonArray().apply { add(entry) })
                }
                "/sync/history/remove" to root
            }
            SyncOpType.RATE -> {
                val entry = JsonObject().apply {
                    addProperty("title", op.item.title)
                    addProperty("rating", op.rating ?: 8)
                    add("ids", idsJson)
                }
                val root = JsonObject().apply {
                    add(bucketKey, JsonArray().apply { add(entry) })
                }
                "/sync/ratings" to root
            }
        }
    }

    suspend fun executeSyncOp(
        clientId: String,
        accessToken: String,
        op: PendingSyncOp
    ): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val trimmedId = clientId.trim()
            val (endpoint, payload) = buildSyncRequestPayload(op)

            val req = Request.Builder()
                .url("$baseUrl$endpoint?client_id=$trimmedId&app-name=Sway&app-version=${BuildConfig.VERSION_NAME}")
                .simklHeaders(trimmedId, accessToken)
                .post(payload.toString().toRequestBody(jsonMedia))
                .build()

            HttpProvider.client.newCall(req).execute().use { resp ->
                resp.isSuccessful
            }
        }.getOrDefault(false)
    }

    suspend fun fetchDiscovery(
        clientId: String,
        mediaType: MediaType
    ): List<MediaItem> = withContext(Dispatchers.IO) {
        if (clientId.isBlank()) return@withContext emptyList()
        runCatching {
            val path = when (mediaType) {
                MediaType.SHOW -> "/tv/trending/week"
                MediaType.ANIME -> "/anime/trending/week"
                MediaType.MOVIE -> "/movies/trending/week"
            }
            val req = Request.Builder()
                .url("$baseUrl$path?extended=overview,metadata,tmdb,genres&client_id=${clientId.trim()}")
                .simklHeaders(clientId)
                .get()
                .build()

            HttpProvider.client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@use emptyList()
                val arr = JsonParser.parseString(resp.body?.string().orEmpty())
                    .takeIf { it.isJsonArray }?.asJsonArray ?: return@use emptyList()
                arr.mapNotNull { elem ->
                    if (elem.isJsonObject) runCatching { parseSimklMedia(elem.asJsonObject, mediaType) }.getOrNull() else null
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun parseSimklMedia(obj: JsonObject, mediaType: MediaType): MediaItem? {
        val title = obj.optStr("title") ?: return null
        val year = obj.get("year")?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()
        val idsObj = obj.optObj("ids")
        val simklId = idsObj?.get("simkl")?.takeIf { it.isJsonPrimitive }?.let { runCatching { it.asInt }.getOrNull() }
            ?: idsObj?.get("simkl_id")?.takeIf { it.isJsonPrimitive }?.let { runCatching { it.asInt }.getOrNull() }
        val imdb = idsObj?.optStr("imdb")
        val tmdb = idsObj?.optStr("tmdb")?.toIntOrNull()
        val tvdb = idsObj?.optStr("tvdb")?.toIntOrNull()
        val mal = idsObj?.optStr("mal")?.toIntOrNull()
        val slug = idsObj?.optStr("slug")

        val posterSlug = obj.optStr("poster")
        val fanartSlug = obj.optStr("fanart")
        val posterUrl = when {
            !posterSlug.isNullOrBlank() -> "https://simkl.in/posters/${posterSlug}_m.jpg"
            !imdb.isNullOrBlank() -> HttpProvider.metahubPoster(imdb)
            else -> ""
        }
        val backdropUrl = when {
            !fanartSlug.isNullOrBlank() -> "https://simkl.in/fanart/${fanartSlug}_medium.jpg"
            !imdb.isNullOrBlank() -> HttpProvider.metahubBackdrop(imdb)
            else -> posterUrl
        }
        val ratingStr = obj.optObj("ratings")?.optObj("imdb")
            ?.get("rating")?.takeIf { it.isJsonPrimitive }?.let { runCatching { it.asDouble }.getOrNull() }
            ?.let { String.format("%.1f", it) }.orEmpty()
        val overview = obj.optStr("overview").orEmpty()
        val genres = obj.optArr("genres")?.mapNotNull {
            it.takeIf { it.isJsonPrimitive }?.asString
        } ?: emptyList()

        val extIds = ExternalIds(
            imdb = imdb,
            simkl = simklId,
            tmdb = tmdb,
            tvdb = tvdb,
            mal = mal,
            slug = slug
        )

        return MediaItem(
            id = extIds.canonicalKey(title, year),
            mediaType = mediaType,
            title = title,
            year = year,
            imdbRating = ratingStr,
            genres = genres,
            overview = overview,
            posterUrl = posterUrl,
            backdropUrl = backdropUrl,
            logoUrl = HttpProvider.metahubLogo(imdb),
            ids = extIds,
            sourceBadge = "Simkl"
        )
    }
}
