package com.burbsklopek.flickdeck.data.local

import android.content.Context
import com.burbsklopek.flickdeck.BuildConfig
import com.burbsklopek.flickdeck.model.*
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonParser
import com.google.gson.reflect.TypeToken
import java.io.File

data class StoredCredentials(
    val traktClientId: String = BuildConfig.DEFAULT_TRAKT_CLIENT_ID,
    val traktClientSecret: String = BuildConfig.DEFAULT_TRAKT_CLIENT_SECRET,
    val traktAccessToken: String = "",
    val traktRefreshToken: String = "",
    val traktExpiresAtMs: Long = 0L,
    val traktUsername: String = "",
    val traktAvatarUrl: String? = null,
    val traktLastSyncMs: Long = 0L,

    val simklClientId: String = BuildConfig.DEFAULT_SIMKL_CLIENT_ID,
    val simklAccessToken: String = "",
    val simklRefreshToken: String = "",
    val simklExpiresAtMs: Long = 0L,
    val simklUsername: String = "",
    val simklAvatarUrl: String? = null,
    val simklLastSyncMs: Long = 0L,

    val tmdbApiKey: String = BuildConfig.DEFAULT_TMDB_API_KEY
)

/**
 * Null-safe JSON parsers guarding against Gson's Unsafe.allocateInstance bypassing Kotlin
 * default constructor values when reading partial, older, or renamed-enum JSON payloads.
 */
object FlickDeckJsonSanitizer {

    private inline fun <reified E : Enum<E>> parseEnumSafe(raw: String?, default: E): E {
        if (raw.isNullOrBlank()) return default
        return enumValues<E>().firstOrNull { it.name.equals(raw, ignoreCase = true) } ?: default
    }

    fun parseSettings(rawJson: String?): AppSettings {
        if (rawJson.isNullOrBlank()) return AppSettings()
        return runCatching {
            val obj = JsonParser.parseString(rawJson).asJsonObject
            val defaults = AppSettings()
            val themeRaw = obj.get("themePreset")?.takeIf { !it.isJsonNull }?.asString
            val mappedTheme = if (themeRaw == "CYBER_MATCHA") {
                ThemePreset.AURORA_PRISM
            } else {
                parseEnumSafe(themeRaw, defaults.themePreset)
            }
            AppSettings(
                themePreset = mappedTheme,
                fontPairing = parseEnumSafe(
                    obj.get("fontPairing")?.takeIf { !it.isJsonNull }?.asString,
                    defaults.fontPairing
                ),
                glassIntensity = parseEnumSafe(
                    obj.get("glassIntensity")?.takeIf { !it.isJsonNull }?.asString,
                    defaults.glassIntensity
                ),
                backdropBlurEnabled = obj.get("backdropBlurEnabled")?.takeIf { !it.isJsonNull }?.asBoolean
                    ?: defaults.backdropBlurEnabled,
                cardShadowGlow = obj.get("cardShadowGlow")?.takeIf { !it.isJsonNull }?.asBoolean
                    ?: defaults.cardShadowGlow,
                swipeSpeed = parseEnumSafe(
                    obj.get("swipeSpeed")?.takeIf { !it.isJsonNull }?.asString,
                    defaults.swipeSpeed
                ),
                controlScheme = parseEnumSafe(
                    obj.get("controlScheme")?.takeIf { !it.isJsonNull }?.asString,
                    defaults.controlScheme
                ),
                defaultVideoQuality = parseEnumSafe(
                    obj.get("defaultVideoQuality")?.takeIf { !it.isJsonNull }?.asString,
                    defaults.defaultVideoQuality
                ),
                syncTargetMode = parseEnumSafe(
                    obj.get("syncTargetMode")?.takeIf { !it.isJsonNull }?.asString,
                    defaults.syncTargetMode
                ),
                hideAlreadyTracked = obj.get("hideAlreadyTracked")?.takeIf { !it.isJsonNull }?.asBoolean
                    ?: defaults.hideAlreadyTracked
            )
        }.getOrElse { AppSettings() }
    }

    fun parseCredentials(rawJson: String?): StoredCredentials {
        if (rawJson.isNullOrBlank()) return StoredCredentials()
        return runCatching {
            val obj = JsonParser.parseString(rawJson).asJsonObject
            fun str(key: String, fallback: String = ""): String =
                obj.get(key)?.takeIf { !it.isJsonNull }?.asString?.takeIf { it.isNotBlank() } ?: fallback
            fun nullableStr(key: String): String? =
                obj.get(key)?.takeIf { !it.isJsonNull }?.asString?.takeIf { it.isNotBlank() }
            fun longVal(key: String): Long =
                obj.get(key)?.takeIf { !it.isJsonNull }?.asLong ?: 0L

            StoredCredentials(
                traktClientId = str("traktClientId", BuildConfig.DEFAULT_TRAKT_CLIENT_ID),
                traktClientSecret = str("traktClientSecret", BuildConfig.DEFAULT_TRAKT_CLIENT_SECRET),
                traktAccessToken = str("traktAccessToken"),
                traktRefreshToken = str("traktRefreshToken"),
                traktExpiresAtMs = longVal("traktExpiresAtMs"),
                traktUsername = str("traktUsername"),
                traktAvatarUrl = nullableStr("traktAvatarUrl"),
                traktLastSyncMs = longVal("traktLastSyncMs"),
                simklClientId = str("simklClientId", BuildConfig.DEFAULT_SIMKL_CLIENT_ID),
                simklAccessToken = str("simklAccessToken"),
                simklRefreshToken = str("simklRefreshToken"),
                simklExpiresAtMs = longVal("simklExpiresAtMs"),
                simklUsername = str("simklUsername"),
                simklAvatarUrl = nullableStr("simklAvatarUrl"),
                simklLastSyncMs = longVal("simklLastSyncMs"),
                tmdbApiKey = str("tmdbApiKey", BuildConfig.DEFAULT_TMDB_API_KEY)
            )
        }.getOrElse { StoredCredentials() }
    }

    @Suppress("UselessCallOnNotNull")
    fun sanitizeMediaItem(raw: MediaItem?): MediaItem? {
        if (raw == null) return null
        val safeTitle = (raw.title as String?)?.takeIf { it.isNotBlank() } ?: return null
        val safeId = (raw.id as String?)?.takeIf { it.isNotBlank() } ?: "title:${safeTitle.lowercase()}"
        return MediaItem(
            id = safeId,
            mediaType = (raw.mediaType as MediaType?) ?: MediaType.SHOW,
            title = safeTitle,
            year = (raw.year as String?).orEmpty(),
            status = (raw.status as String?).orEmpty(),
            imdbRating = (raw.imdbRating as String?).orEmpty(),
            runtime = (raw.runtime as String?).orEmpty(),
            genres = (raw.genres as List<String>?)?.filterNotNull() ?: emptyList(),
            overview = (raw.overview as String?).orEmpty(),
            cast = (raw.cast as List<String>?)?.filterNotNull() ?: emptyList(),
            creators = (raw.creators as List<String>?)?.filterNotNull() ?: emptyList(),
            awards = raw.awards,
            network = raw.network,
            seasonsCount = raw.seasonsCount,
            posterUrl = (raw.posterUrl as String?).orEmpty(),
            backdropUrl = (raw.backdropUrl as String?).orEmpty(),
            logoUrl = raw.logoUrl,
            trailerYoutubeId = raw.trailerYoutubeId,
            ids = (raw.ids as ExternalIds?) ?: ExternalIds(),
            sourceBadge = (raw.sourceBadge as String?) ?: "IMDb"
        )
    }
}

class FlickDeckStore(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences("flickdeck_prefs", Context.MODE_PRIVATE)
    private val gson: Gson = GsonBuilder().disableHtmlEscaping().create()

    private val libraryFile = File(appContext.filesDir, "tracked_library_v1.json")
    private val syncQueueFile = File(appContext.filesDir, "pending_sync_ops_v1.json")
    private val fileLock = Any()

    fun loadSettings(): AppSettings =
        FlickDeckJsonSanitizer.parseSettings(prefs.getString("app_settings_v1", null))

    fun saveSettings(settings: AppSettings) {
        prefs.edit().putString("app_settings_v1", gson.toJson(settings)).apply()
    }

    fun loadCredentials(): StoredCredentials =
        FlickDeckJsonSanitizer.parseCredentials(prefs.getString("credentials_v1", null))

    fun saveCredentials(creds: StoredCredentials) {
        prefs.edit().putString("credentials_v1", gson.toJson(creds)).apply()
    }

    @Suppress("UselessCallOnNotNull")
    fun loadTrackedEntries(): Map<String, TrackedEntry> = synchronized(fileLock) {
        if (!libraryFile.exists()) return emptyMap()
        return runCatching {
            val listType = object : TypeToken<List<TrackedEntry>>() {}.type
            val list: List<TrackedEntry?> = gson.fromJson(libraryFile.readText(), listType) ?: emptyList()
            list.mapNotNull { entry ->
                if (entry == null) return@mapNotNull null
                val item = FlickDeckJsonSanitizer.sanitizeMediaItem(entry.item) ?: return@mapNotNull null
                val action = (entry.action as SwipeAction?) ?: SwipeAction.TO_WATCH
                entry.copy(item = item, action = action)
            }.associateBy { it.item.id }
        }.getOrDefault(emptyMap())
    }

    fun saveTrackedEntries(entries: Map<String, TrackedEntry>) = synchronized(fileLock) {
        runCatching {
            val tmp = File(appContext.filesDir, "tracked_library_v1.tmp")
            tmp.writeText(gson.toJson(entries.values.toList()))
            tmp.renameTo(libraryFile)
        }
    }

    @Suppress("UselessCallOnNotNull")
    fun loadPendingSyncOps(): List<PendingSyncOp> = synchronized(fileLock) {
        if (!syncQueueFile.exists()) return emptyList()
        return runCatching {
            val listType = object : TypeToken<List<PendingSyncOp>>() {}.type
            val rawList: List<PendingSyncOp?> = gson.fromJson(syncQueueFile.readText(), listType) ?: emptyList()
            rawList.mapNotNull { op ->
                if (op == null) return@mapNotNull null
                val item = FlickDeckJsonSanitizer.sanitizeMediaItem(op.item) ?: return@mapNotNull null
                val opId = (op.opId as String?)?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val provider = (op.provider as SyncProvider?) ?: return@mapNotNull null
                val opType = (op.opType as SyncOpType?) ?: return@mapNotNull null
                op.copy(opId = opId, provider = provider, opType = opType, item = item)
            }
        }.getOrDefault(emptyList())
    }

    fun savePendingSyncOps(ops: List<PendingSyncOp>) = synchronized(fileLock) {
        runCatching {
            val tmp = File(appContext.filesDir, "pending_sync_ops_v1.tmp")
            tmp.writeText(gson.toJson(ops))
            tmp.renameTo(syncQueueFile)
        }
    }
}
