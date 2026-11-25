package com.burbsklopek.flickdeck.data.remote

import com.burbsklopek.flickdeck.model.*
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.net.URLEncoder
import java.time.Year

class CatalogService {
    private val cinemetaBase = "https://v3-cinemeta.strem.io"
    private val kitsuAnimeBase = "https://anime-kitsu.strem.fun"

    val supportedGenres = listOf(
        "All", "Sci-Fi", "Drama", "Thriller", "Crime", "Comedy",
        "Fantasy", "Mystery", "Action", "Horror", "Animation", "Documentary"
    )

    suspend fun fetchCatalog(
        mediaType: MediaType,
        category: FeedCategory,
        genre: String = "All",
        skip: Int = 0
    ): List<MediaItem> = fetchCatalogPage(
        mediaType = mediaType,
        category = category,
        genre = genre,
        pageIndex = (skip / 50).coerceAtLeast(0)
    )

    suspend fun fetchCatalogPage(
        mediaType: MediaType,
        category: FeedCategory,
        genre: String = "All",
        pageIndex: Int = 0
    ): List<MediaItem> = withContext(Dispatchers.IO) {
        val onlineItems = when (mediaType) {
            MediaType.ANIME -> fetchAnimeCatalog(category, pageIndex)
            MediaType.SHOW, MediaType.MOVIE -> fetchCinemetaCatalog(mediaType, category, genre, pageIndex)
        }
        if (onlineItems.isNotEmpty()) {
            onlineItems
        } else if (pageIndex == 0) {
            val seeds = fallbackSeedDeck(mediaType)
            if (genre == "All" || genre.isBlank()) {
                seeds
            } else {
                seeds.filter { seed -> seed.genres.any { it.equals(genre, ignoreCase = true) } }
                    .ifEmpty { seeds }
            }
        } else {
            emptyList()
        }
    }

    fun buildCinemetaUrls(
        mediaType: MediaType,
        category: FeedCategory,
        genre: String,
        pageIndex: Int
    ): List<String> {
        if (pageIndex < 0) return emptyList()
        val typeSegment = if (mediaType == MediaType.MOVIE) "movie" else "series"
        val hasGenre = genre != "All" && genre.isNotBlank()
        val encodedGenre = if (hasGenre) URLEncoder.encode(genre, "UTF-8").replace("+", "%20") else ""
        val skip = pageIndex * 50

        return when (category) {
            FeedCategory.NEW_RELEASES -> {
                if (!hasGenre) {
                    // Cinemeta's `year` catalog uses `genre=<YYYY>` for per-year pagination (50 items/year).
                    val currentYear = runCatching { Year.now().value }.getOrDefault(2026).coerceAtLeast(2025)
                    when {
                        pageIndex == 0 -> listOf(
                            "$cinemetaBase/catalog/$typeSegment/year.json",
                            "$cinemetaBase/catalog/$typeSegment/year/genre=$currentYear.json"
                        )
                        pageIndex <= 15 -> {
                            val targetYear = currentYear - pageIndex
                            listOf("$cinemetaBase/catalog/$typeSegment/year/genre=$targetYear.json")
                        }
                        else -> emptyList()
                    }
                } else {
                    // Cinemeta's `year` catalog returns 0 for named genres (e.g. `year/genre=Sci-Fi.json`).
                    // Query `top` for the selected genre with 50-item pagination and sort newest-first.
                    val suffix = if (skip > 0) "genre=$encodedGenre&skip=$skip" else "genre=$encodedGenre"
                    listOf("$cinemetaBase/catalog/$typeSegment/top/$suffix.json")
                }
            }

            FeedCategory.TOP_RATED -> {
                val extras = buildList {
                    if (hasGenre) add("genre=$encodedGenre")
                    if (skip > 0) add("skip=$skip")
                }
                val suffix = if (extras.isEmpty()) "" else "/${extras.joinToString("&")}"
                listOf("$cinemetaBase/catalog/$typeSegment/imdbRating$suffix.json")
            }

            FeedCategory.TRENDING, FeedCategory.FOR_YOU -> {
                val extras = buildList {
                    if (hasGenre) add("genre=$encodedGenre")
                    if (skip > 0) add("skip=$skip")
                }
                val suffix = if (extras.isEmpty()) "" else "/${extras.joinToString("&")}"
                listOf("$cinemetaBase/catalog/$typeSegment/top$suffix.json")
            }
        }
    }

    fun buildAnimeUrls(category: FeedCategory, pageIndex: Int): List<String> {
        if (pageIndex < 0) return emptyList()
        return if (pageIndex == 0) {
            when (category) {
                FeedCategory.TRENDING, FeedCategory.FOR_YOU -> listOf(
                    "$kitsuAnimeBase/catalog/anime/kitsu-anime-trending.json",
                    "$kitsuAnimeBase/catalog/anime/kitsu-anime-popular.json"
                )
                FeedCategory.TOP_RATED -> listOf(
                    "$kitsuAnimeBase/catalog/anime/kitsu-anime-rating.json",
                    "$kitsuAnimeBase/catalog/anime/kitsu-anime-list.json"
                )
                FeedCategory.NEW_RELEASES -> listOf(
                    "$kitsuAnimeBase/catalog/anime/kitsu-anime-airing.json",
                    "$kitsuAnimeBase/catalog/anime/kitsu-anime-trending.json"
                )
            }
        } else if (pageIndex <= 12) {
            val kitsuSkip = pageIndex * 20
            val animSkip = (pageIndex - 1) * 50
            val cinemetaAnimSuffix = if (animSkip > 0) "genre=Animation&skip=$animSkip" else "genre=Animation"
            listOf(
                "$kitsuAnimeBase/catalog/anime/kitsu-anime-list/skip=$kitsuSkip.json",
                "$cinemetaBase/catalog/series/top/$cinemetaAnimSuffix.json"
            )
        } else {
            emptyList()
        }
    }

    suspend fun searchCatalog(query: String, mediaType: MediaType): List<MediaItem> =
        withContext(Dispatchers.IO) {
            val trimmed = query.trim()
            if (trimmed.isBlank()) return@withContext emptyList()
            val encoded = URLEncoder.encode(trimmed, "UTF-8").replace("+", "%20")

            if (mediaType == MediaType.ANIME) {
                val kitsuMatches = fetchStremioMetas(
                    url = "$kitsuAnimeBase/catalog/anime/kitsu-anime-list/search=$encoded.json",
                    mediaType = MediaType.ANIME,
                    badge = "Anime · 4K"
                )
                if (kitsuMatches.isNotEmpty()) return@withContext kitsuMatches

                return@withContext fetchStremioMetas(
                    url = "$cinemetaBase/catalog/series/top/search=$encoded.json",
                    mediaType = MediaType.ANIME,
                    badge = "Anime · 4K"
                )
            }

            val typeSegment = if (mediaType == MediaType.MOVIE) "movie" else "series"
            fetchStremioMetas(
                url = "$cinemetaBase/catalog/$typeSegment/top/search=$encoded.json",
                mediaType = mediaType,
                badge = "IMDb · 4K"
            )
        }

    suspend fun enrichItemDetails(item: MediaItem): MediaItem? = withContext(Dispatchers.IO) {
        val imdb = item.ids.imdb?.takeIf { it.startsWith("tt") } ?: return@withContext item
        val primaryType = if (item.mediaType == MediaType.MOVIE) "movie" else "series"
        fetchCinemetaMeta(item, imdb, primaryType)
            ?: if (item.mediaType == MediaType.ANIME) fetchCinemetaMeta(item, imdb, "movie") else null
    }

    suspend fun resolveTrailerYoutubeId(item: MediaItem): String? = withContext(Dispatchers.IO) {
        item.trailerYoutubeId?.takeIf { isValidYoutubeId(it) }?.let { return@withContext it }

        // 1. Try Cinemeta full meta endpoint if we have an IMDb ID
        val fromMeta = enrichItemDetails(item)?.trailerYoutubeId?.takeIf { isValidYoutubeId(it) }
        if (fromMeta != null) return@withContext fromMeta

        // 2. Resolve the exact official trailer videoId from YouTube search so Cobalt/TizenTube
        // opens the exact video directly instead of landing on the YouTube TV home screen.
        val typeHint = when (item.mediaType) {
            MediaType.MOVIE -> "movie"
            MediaType.ANIME -> "anime"
            MediaType.SHOW -> "series"
        }
        val yearHint = Regex("(19|20)\\d{2}").find(item.year)?.value.orEmpty()
        val query = listOf(item.title, yearHint, typeHint, "official trailer")
            .filter { it.isNotBlank() }
            .joinToString(" ")
        val encoded = URLEncoder.encode(query, "UTF-8")

        runCatching {
            val req = Request.Builder()
                .url("https://www.youtube.com/results?search_query=$encoded")
                .header(
                    "User-Agent",
                    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"
                )
                .header("Accept-Language", "en-US,en;q=0.9")
                .get()
                .build()
            HttpProvider.client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@use null
                val html = resp.body?.string().orEmpty()
                Regex("\"videoId\":\"([A-Za-z0-9_-]{11})\"").find(html)?.groupValues?.get(1)
            }
        }.getOrNull()
    }

    internal fun isValidYoutubeId(id: String?): Boolean =
        !id.isNullOrBlank() && Regex("^[A-Za-z0-9_-]{11}$").matches(id.trim())

    private fun fetchCinemetaMeta(item: MediaItem, imdb: String, typeSegment: String): MediaItem? {
        return runCatching {
            val req = Request.Builder()
                .url("$cinemetaBase/meta/$typeSegment/$imdb.json")
                .get()
                .build()
            HttpProvider.client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@use null
                val root = JsonParser.parseString(resp.body?.string().orEmpty())
                    .takeIf { it.isJsonObject }?.asJsonObject ?: return@use null
                val meta = root.optObj("meta") ?: return@use null
                val parsed = parseStremioMetaItem(meta, item.mediaType, item.sourceBadge) ?: return@use null
                if (parsed.ids.imdb != null && parsed.ids.imdb != imdb) return@use null
                val seasonsCount = meta.optArr("videos")
                    ?.mapNotNull { v ->
                        v.takeIf { it.isJsonObject }?.asJsonObject
                            ?.get("season")?.takeIf { it.isJsonPrimitive }?.let { runCatching { it.asInt }.getOrNull() }
                    }
                    ?.filter { it > 0 }
                    ?.distinct()
                    ?.size
                    ?.takeIf { it > 0 }

                item.copy(
                    year = parsed.year.ifBlank { item.year },
                    status = parsed.status.ifBlank { item.status },
                    imdbRating = parsed.imdbRating.ifBlank { item.imdbRating },
                    runtime = parsed.runtime.ifBlank { item.runtime },
                    genres = parsed.genres.ifEmpty { item.genres },
                    overview = parsed.overview.ifBlank { item.overview },
                    cast = parsed.cast.ifEmpty { item.cast },
                    creators = parsed.creators.ifEmpty { item.creators },
                    awards = parsed.awards ?: item.awards,
                    seasonsCount = seasonsCount ?: item.seasonsCount,
                    posterUrl = item.posterUrl.ifBlank { parsed.posterUrl },
                    backdropUrl = item.backdropUrl.ifBlank { parsed.backdropUrl },
                    logoUrl = item.logoUrl ?: parsed.logoUrl,
                    trailerYoutubeId = item.trailerYoutubeId ?: parsed.trailerYoutubeId,
                    ids = item.ids.mergeWith(parsed.ids)
                )
            }
        }.getOrNull()
    }

    private fun fetchCinemetaCatalog(
        mediaType: MediaType,
        category: FeedCategory,
        genre: String,
        pageIndex: Int
    ): List<MediaItem> {
        val urls = buildCinemetaUrls(mediaType, category, genre, pageIndex)
        if (urls.isEmpty()) return emptyList()

        val merged = LinkedHashMap<String, MediaItem>()
        for (url in urls) {
            fetchStremioMetas(url, mediaType, "IMDb · 4K").forEach { item ->
                merged.putIfAbsent(item.id, item)
            }
        }

        val items = merged.values.toList()
        val hasGenre = genre != "All" && genre.isNotBlank()
        return if (category == FeedCategory.NEW_RELEASES && hasGenre) {
            items.sortedByDescending { extractLatestYear(it.year) }
        } else {
            items
        }
    }

    private fun fetchAnimeCatalog(category: FeedCategory, pageIndex: Int): List<MediaItem> {
        val urls = buildAnimeUrls(category, pageIndex)
        if (urls.isEmpty()) return emptyList()

        val merged = LinkedHashMap<String, MediaItem>()
        for (url in urls) {
            fetchStremioMetas(url, MediaType.ANIME, "Anime · 4K").forEach { item ->
                merged.putIfAbsent(item.id, item)
            }
        }
        return merged.values.toList()
    }

    private fun fetchStremioMetas(
        url: String,
        mediaType: MediaType,
        badge: String
    ): List<MediaItem> {
        return runCatching {
            val req = Request.Builder().url(url).get().build()
            HttpProvider.client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@use emptyList()
                val root = JsonParser.parseString(resp.body?.string().orEmpty())
                    .takeIf { it.isJsonObject }?.asJsonObject ?: return@use emptyList()
                val metas = root.optArr("metas") ?: return@use emptyList()
                metas.mapNotNull { elem ->
                    if (elem.isJsonObject) {
                        runCatching { parseStremioMetaItem(elem.asJsonObject, mediaType, badge) }.getOrNull()
                    } else null
                }
            }
        }.getOrDefault(emptyList())
    }

    internal fun parseStremioMetaItem(
        obj: JsonObject,
        mediaType: MediaType,
        badge: String = "IMDb · 4K"
    ): MediaItem? {
        val title = obj.optStr("name")?.takeIf { it.isNotBlank() } ?: return null
        val rawId = obj.optStr("id")
        val imdb = obj.optStr("imdb_id")?.takeIf { it.startsWith("tt") }
            ?: rawId?.takeIf { it.startsWith("tt") }
        val tvdb = runCatching { obj.optStr("tvdb_id")?.toIntOrNull() }.getOrNull()
        val tmdb = runCatching { obj.optStr("moviedb_id")?.toIntOrNull() }.getOrNull()
        val slug = obj.optStr("slug")
            ?: rawId?.takeIf { !it.startsWith("tt") && it.isNotBlank() }

        val year = obj.optStr("releaseInfo")
            ?: obj.optStr("year")
            ?: ""
        val imdbRating = obj.optStr("imdbRating").orEmpty()
        val runtime = obj.optStr("runtime").orEmpty()
        val status = obj.optStr("status").orEmpty()
        val overview = obj.optStr("description").orEmpty()
        val awards = obj.optStr("awards")

        val genres = (obj.optArr("genres") ?: obj.optArr("genre"))
            ?.mapNotNull { it.takeIf { it.isJsonPrimitive }?.asString }
            ?: emptyList()
        val cast = obj.optArr("cast")
            ?.mapNotNull { it.takeIf { it.isJsonPrimitive }?.asString }
            ?.take(6) ?: emptyList()
        val creators = (obj.optArr("director") ?: obj.optArr("writer"))
            ?.mapNotNull { it.takeIf { it.isJsonPrimitive }?.asString }
            ?.take(3) ?: emptyList()

        val trailersArr = obj.optArr("trailers")
        val preferredTrailerId = trailersArr
            ?.mapNotNull { it.takeIf { it.isJsonObject }?.asJsonObject }
            ?.sortedByDescending { it.optStr("type").equals("Trailer", ignoreCase = true) }
            ?.mapNotNull { it.optStr("source")?.trim() }
            ?.firstOrNull { isValidYoutubeId(it) }
        val streamTrailerId = obj.optArr("trailerStreams")
            ?.mapNotNull { it.takeIf { it.isJsonObject }?.asJsonObject?.optStr("ytId")?.trim() }
            ?.firstOrNull { isValidYoutubeId(it) }
        val trailerYoutubeId = preferredTrailerId ?: streamTrailerId

        val rawPoster = obj.optStr("poster").orEmpty()
        val rawBackground = obj.optStr("background").orEmpty()

        val posterUrl = when {
            !imdb.isNullOrBlank() -> HttpProvider.metahubPoster(imdb)
            rawPoster.isNotBlank() -> rawPoster
            else -> ""
        }
        val backdropUrl = when {
            !imdb.isNullOrBlank() -> HttpProvider.metahubBackdrop(imdb)
            rawBackground.isNotBlank() -> rawBackground
            else -> posterUrl
        }
        val logoUrl = if (!imdb.isNullOrBlank()) {
            HttpProvider.metahubLogo(imdb)
        } else {
            obj.optStr("logo")
        }

        val extIds = ExternalIds(imdb = imdb, tmdb = tmdb, tvdb = tvdb, slug = slug)
        return MediaItem(
            id = extIds.canonicalKey(title, year),
            mediaType = mediaType,
            title = title,
            year = year,
            status = status,
            imdbRating = imdbRating,
            runtime = runtime,
            genres = genres,
            overview = overview,
            cast = cast,
            creators = creators,
            awards = awards,
            posterUrl = posterUrl,
            backdropUrl = backdropUrl,
            logoUrl = logoUrl,
            trailerYoutubeId = trailerYoutubeId,
            ids = extIds,
            sourceBadge = badge
        )
    }

    internal fun extractLatestYear(yearStr: String): Int {
        val matches = Regex("(19|20)\\d{2}").findAll(yearStr).mapNotNull { it.value.toIntOrNull() }.toList()
        if (matches.isEmpty()) return 0
        // Ongoing series like "2024–" should rank as current year
        if (yearStr.trimEnd().endsWith("–") || yearStr.trimEnd().endsWith("-")) {
            return maxOf(matches.maxOrNull() ?: 0, 2025)
        }
        return matches.maxOrNull() ?: 0
    }

    fun fallbackSeedDeck(mediaType: MediaType = MediaType.SHOW): List<MediaItem> {
        data class Seed(
            val imdb: String,
            val type: MediaType,
            val title: String,
            val year: String,
            val rating: String,
            val genres: List<String>,
            val overview: String,
            val trailer: String? = null
        )
        val seeds = listOf(
            // TV Shows
            Seed("tt11280740", MediaType.SHOW, "Severance", "2022–", "8.7", listOf("Sci-Fi", "Drama", "Mystery"),
                "Mark leads a team of office workers whose memories have been surgically divided between their work and personal lives.", "xEQP4VVuyrY"),
            Seed("tt2788316", MediaType.SHOW, "Shōgun", "2024–", "8.6", listOf("Drama", "Adventure", "History"),
                "When a mysterious European ship is found marooned in a nearby fishing village, Lord Yoshii Toranaga discovers secrets that could tip the scales of power.", "yAN5uspO_hk"),
            Seed("tt14452776", MediaType.SHOW, "The Bear", "2022–", "8.5", listOf("Drama", "Comedy"),
                "A young chef from the fine dining world returns to Chicago to run his family's sandwich shop amid chaos, grief, and culinary obsession.", "gBmkI4jlaIo"),
            Seed("tt11126994", MediaType.SHOW, "Arcane", "2021–2024", "9.0", listOf("Animation", "Action", "Sci-Fi"),
                "Set in utopian Piltover and the oppressed underground of Zaun, the story follows the origins of two iconic League champions and the power that tears them apart.", "fXmAurh012s"),
            Seed("tt7660850", MediaType.SHOW, "Succession", "2018–2023", "8.8", listOf("Drama"),
                "The Roy family is known for controlling the biggest media and entertainment company in the world, until their aging patriarch steps back.", "OzYxJV_rmE8"),
            Seed("tt9253284", MediaType.SHOW, "Andor", "2022–2025", "8.4", listOf("Sci-Fi", "Action", "Drama"),
                "Prequel series to Rogue One following Cassian Andor's tense spy-thriller journey as a burgeoning rebellion takes shape against the Empire.", "cKOegEuCcfw"),
            Seed("tt14688458", MediaType.SHOW, "Silo", "2023–", "8.1", listOf("Sci-Fi", "Drama", "Mystery"),
                "Men and women live in a giant silo underground with strict regulations they believe are meant to protect them from a toxic and ruined world.", "8ZYhuvIv1pA"),
            Seed("tt12637874", MediaType.SHOW, "Fallout", "2024–", "8.3", listOf("Sci-Fi", "Action", "Adventure"),
                "In a future, post-apocalyptic Los Angeles brought about by nuclear decimation, citizens must live in underground bunkers to survive.", "V-mugKDQDlg"),
            Seed("tt5753856", MediaType.SHOW, "Dark", "2017–2020", "8.7", listOf("Sci-Fi", "Mystery", "Thriller"),
                "A family saga with a supernatural twist, set in a German town where the disappearance of two young children exposes intertwined secrets across generations.", "rrwycJ08PSA"),
            Seed("tt7366338", MediaType.SHOW, "Chernobyl", "2019", "9.3", listOf("Drama", "History", "Thriller"),
                "In April 1986, an explosion at the Chernobyl nuclear power plant in the USSR becomes one of the world's worst man-made catastrophes.", "s9APLXM9Ei8"),
            Seed("tt5875444", MediaType.SHOW, "Slow Horses", "2022–", "8.2", listOf("Thriller", "Drama", "Crime"),
                "Follows a dysfunctional team of MI5 agents—and their obnoxious boss, the notorious Jackson Lamb—as they defend England from sinister forces.", "O9ZJChzPn0U"),
            Seed("tt3581920", MediaType.SHOW, "The Last of Us", "2023–", "8.7", listOf("Drama", "Action", "Sci-Fi"),
                "After a global pandemic destroys civilization, a hardened survivor takes charge of a 14-year-old girl who may be humanity's last hope.", "uLtkt8BonwM"),
            // Movies
            Seed("tt15239678", MediaType.MOVIE, "Dune: Part Two", "2024", "8.5", listOf("Sci-Fi", "Action", "Drama"),
                "Paul Atreides unites with Chani and the Fremen while seeking revenge against the conspirators who destroyed his family.", "Way9Dexny3w"),
            Seed("tt15398776", MediaType.MOVIE, "Oppenheimer", "2023", "8.3", listOf("Drama", "Thriller"),
                "The story of American scientist J. Robert Oppenheimer and his role in the development of the atomic bomb.", "uYPbbksJxIg"),
            Seed("tt9362722", MediaType.MOVIE, "Spider-Man: Across the Spider-Verse", "2023", "8.6", listOf("Animation", "Action", "Sci-Fi"),
                "Miles Morales catapults across the Multiverse, where he encounters a team of Spider-People charged with protecting its very existence.", "shW9i6k8cB0"),
            Seed("tt6751668", MediaType.MOVIE, "Parasite", "2019", "8.5", listOf("Thriller", "Drama", "Comedy"),
                "Greed and class discrimination threaten the newly formed symbiotic relationship between the wealthy Park family and the destitute Kim clan.", "5xH0HfJHsaY"),
            // Anime
            Seed("tt22248376", MediaType.ANIME, "Frieren: Beyond Journey's End", "2023–", "8.9", listOf("Animation", "Fantasy", "Adventure"),
                "After the party of heroes defeated the Demon King, elven mage Frieren embarks on a new journey to understand humanity and the passage of time.", "ZEkwCGJ3o7M"),
            Seed("tt2560140", MediaType.ANIME, "Attack on Titan", "2013–2023", "9.1", listOf("Animation", "Action", "Drama"),
                "After his hometown is destroyed, young Eren Jaeger vows to cleanse the earth of the giant humanoid Titans that have brought humanity to the brink of extinction.", "MGRm4IzK1SQ"),
            Seed("tt19844504", MediaType.ANIME, "Solo Leveling", "2024–", "8.4", listOf("Animation", "Action", "Fantasy"),
                "In a world of gifted hunters and monsters, a weak hunter gains a mysterious power that lets him level up without limits.", "bssSj4cKsrI"),
            Seed("tt13616990", MediaType.ANIME, "Chainsaw Man", "2022–", "8.3", listOf("Animation", "Action", "Horror"),
                "Following a betrayal, a young man left for dead is reborn as a powerful devil-human hybrid after merging with his pet devil.", "q15CRdE5Bv0")
        )
        return seeds.filter { it.type == mediaType }.map { s ->
            val ids = ExternalIds(imdb = s.imdb)
            MediaItem(
                id = ids.canonicalKey(s.title, s.year),
                mediaType = s.type,
                title = s.title,
                year = s.year,
                imdbRating = s.rating,
                genres = s.genres,
                overview = s.overview,
                posterUrl = HttpProvider.metahubPoster(s.imdb),
                backdropUrl = HttpProvider.metahubBackdrop(s.imdb),
                logoUrl = HttpProvider.metahubLogo(s.imdb),
                trailerYoutubeId = s.trailer,
                ids = ids,
                sourceBadge = "Curated 4K"
            )
        }
    }
}
