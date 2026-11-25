package com.burbsklopek.flickdeck

import com.burbsklopek.flickdeck.data.local.StoredCredentials
import com.burbsklopek.flickdeck.data.remote.CatalogService
import com.burbsklopek.flickdeck.data.remote.SimklApiClient
import com.burbsklopek.flickdeck.data.local.FlickDeckJsonSanitizer
import com.burbsklopek.flickdeck.model.*
import com.burbsklopek.flickdeck.ui.components.TrailerLauncher
import com.burbsklopek.flickdeck.viewmodel.DeckFilterEngine
import com.google.gson.JsonParser
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class FlickDeckDomainTest {

    @Test
    fun canonicalKeyPrioritizesImdbOverOtherIds() {
        val ids = ExternalIds(imdb = "tt11280740", tmdb = 95396, simkl = 12345)
        assertEquals("imdb:tt11280740", ids.canonicalKey("Severance", "2022"))
    }

    @Test
    fun mergeExternalIdsPreservesNonNullFields() {
        val fromCatalog = ExternalIds(imdb = "tt0903747", tmdb = 1396)
        val fromSimkl = ExternalIds(imdb = "tt0903747", simkl = 5740, tvdb = 81189)
        val merged = fromCatalog.mergeWith(fromSimkl)

        assertEquals("tt0903747", merged.imdb)
        assertEquals(1396, merged.tmdb)
        assertEquals(5740, merged.simkl)
        assertEquals(81189, merged.tvdb)
    }

    @Test
    fun defaultAppSettingsEnforce2160pAndSnappyAnimations() {
        val defaults = AppSettings()
        assertEquals(VideoQuality.UHD_2160P, defaults.defaultVideoQuality)
        assertEquals(2160, defaults.defaultVideoQuality.heightPx)
        assertEquals(SwipeSpeed.SNAPPY, defaults.swipeSpeed)
        assertEquals(SwipeControlScheme.TINDER_3WAY, defaults.controlScheme)
        assertTrue(defaults.backdropBlurEnabled)
    }

    @Test
    fun fallbackSeedDeckHasRichArtworkAndValidIds() {
        val seeds = CatalogService().fallbackSeedDeck()
        assertTrue(seeds.size >= 10)
        seeds.forEach { item ->
            assertTrue(item.id.startsWith("imdb:tt"))
            assertTrue(item.posterUrl.contains("metahub.space/poster/large/"))
            assertTrue(item.backdropUrl.contains("metahub.space/background/large/"))
            assertNotNull(item.logoUrl)
        }
    }

    @Test
    fun legacyOrPartialSettingsJsonNeverProducesNullEnums() {
        // Legacy v1 JSON had renamed CYBER_MATCHA theme and lacked fontPairing
        val legacyJson = """{"themePreset":"CYBER_MATCHA","backdropBlurEnabled":false}"""
        val settings = FlickDeckJsonSanitizer.parseSettings(legacyJson)

        assertNotNull(settings.themePreset)
        assertNotNull(settings.fontPairing)
        assertNotNull(settings.glassIntensity)
        assertNotNull(settings.swipeSpeed)
        assertNotNull(settings.controlScheme)
        assertEquals(VideoQuality.UHD_2160P, settings.defaultVideoQuality)
        assertFalse(settings.backdropBlurEnabled)
    }

    @Test
    fun partialCredentialsJsonSafelyDefaultsMissingStrings() {
        val partialJson = """{"traktAccessToken":"tok_123"}"""
        val creds = FlickDeckJsonSanitizer.parseCredentials(partialJson)
        assertEquals("tok_123", creds.traktAccessToken)
        assertNotNull(creds.traktClientId)
        assertNotNull(creds.simklClientId)
        assertNotNull(creds.traktUsername)
    }

    @Test
    fun simklAnimeSyncPayloadUsesAnimeBucketForMalItems() {
        val animeItem = MediaItem(
            id = "mal:52991",
            mediaType = MediaType.ANIME,
            title = "Frieren: Beyond Journey's End",
            year = "2023",
            ids = ExternalIds(mal = 52991)
        )
        val op = PendingSyncOp(
            opId = "op-1",
            provider = SyncProvider.SIMKL,
            opType = SyncOpType.ADD_WATCHLIST,
            item = animeItem
        )
        val (endpoint, payload) = SimklApiClient().buildSyncRequestPayload(op)
        assertEquals("/sync/add-to-list", endpoint)
        assertTrue("Expected 'anime' bucket in Simkl payload: $payload", payload.has("anime"))
        val animeArr = payload.getAsJsonArray("anime")
        assertEquals(1, animeArr.size())
        assertEquals("plantowatch", animeArr[0].asJsonObject.get("to").asString)
        assertEquals(52991, animeArr[0].asJsonObject.getAsJsonObject("ids").get("mal").asInt)
    }

    @Test
    fun fallbackSeedDeckSupportsShowsMoviesAndAnime() {
        val svc = CatalogService()
        MediaType.entries.forEach { type ->
            val seeds = svc.fallbackSeedDeck(type)
            assertTrue("Expected non-empty seeds for $type", seeds.isNotEmpty())
            assertTrue(seeds.all { it.mediaType == type && it.posterUrl.isNotBlank() })
        }
    }

    @Test
    fun cinemetaNewReleasesUrlsAvoidBrokenYearGenreComboAndPaginateByYear() {
        val svc = CatalogService()
        // Named genre + NEW_RELEASES must query /top/genre=... rather than /year/genre=Sci-Fi (which returns 0 items)
        val sciFiNewPage0 = svc.buildCinemetaUrls(MediaType.SHOW, FeedCategory.NEW_RELEASES, "Sci-Fi", 0)
        assertEquals(listOf("https://v3-cinemeta.strem.io/catalog/series/top/genre=Sci-Fi.json"), sciFiNewPage0)

        val sciFiNewPage2 = svc.buildCinemetaUrls(MediaType.SHOW, FeedCategory.NEW_RELEASES, "Sci-Fi", 2)
        assertEquals(listOf("https://v3-cinemeta.strem.io/catalog/series/top/genre=Sci-Fi&skip=100.json"), sciFiNewPage2)

        // All genres + NEW_RELEASES paginates across individual release years
        val allNewPage0 = svc.buildCinemetaUrls(MediaType.MOVIE, FeedCategory.NEW_RELEASES, "All", 0)
        assertTrue(allNewPage0.any { it.endsWith("/catalog/movie/year.json") })
        val allNewPage1 = svc.buildCinemetaUrls(MediaType.MOVIE, FeedCategory.NEW_RELEASES, "All", 1)
        assertEquals(1, allNewPage1.size)
        assertTrue(allNewPage1[0].contains("/catalog/movie/year/genre=20"))
    }

    @Test
    fun multiPageDeckReplenishSkipsFullyTrackedPagesInsteadOfEndingReel() = runBlocking {
        // Pages 0, 1, 2 (30 items) are 100% already watched on Trakt; pages 3 & 4 have fresh items
        fun makePage(pageIdx: Int): List<MediaItem> = (0 until 10).map { idx ->
            val num = pageIdx * 10 + idx + 1000
            val imdb = "tt$num"
            MediaItem(
                id = "imdb:$imdb",
                title = "Show $num",
                ids = ExternalIds(imdb = imdb)
            )
        }

        val tracked = (0 until 30).associate { i ->
            val imdb = "tt${1000 + i}"
            val item = MediaItem(id = "imdb:$imdb", title = "Show ${1000 + i}", ids = ExternalIds(imdb = imdb))
            item.id to TrackedEntry(item = item, action = SwipeAction.WATCHED)
        }

        val res = DeckFilterEngine.replenishFromPages(
            initialRaw = emptyList(),
            tracked = tracked,
            hideTracked = true,
            startPage = 0,
            fetchPage = { page -> if (page <= 4) makePage(page) else emptyList() }
        )

        assertFalse(res.exhausted)
        assertEquals(20, res.visibleDeck.size)
        assertEquals("imdb:tt1030", res.visibleDeck.first().id)
        assertEquals(5, res.nextPage)
    }

    @Test
    fun multiPageDeckReplenishStopsOnRepeatedDuplicatePages() = runBlocking {
        val staticBatch = listOf(
            MediaItem(id = "imdb:tt0001", title = "Only Show", ids = ExternalIds(imdb = "tt0001"))
        )
        var calls = 0
        val res = DeckFilterEngine.replenishFromPages(
            initialRaw = emptyList(),
            tracked = mapOf(
                "imdb:tt0001" to TrackedEntry(item = staticBatch[0], action = SwipeAction.WATCHED)
            ),
            hideTracked = true,
            startPage = 0,
            fetchPage = {
                calls++
                staticBatch // Simulates an endpoint ignoring skip= and repeating the same items
            }
        )

        assertTrue(res.exhausted)
        assertTrue(res.visibleDeck.isEmpty())
        assertEquals(3, calls)
    }

    @Test
    fun cinemetaParserHandlesExplicitJsonNullArraysWithoutThrowingClassCastException() {
        val rawCinemetaJson = """
            {
              "id": "tt31510819",
              "imdb_id": "tt31510819",
              "name": "MobLand",
              "releaseInfo": "2025–",
              "imdbRating": "8.4",
              "director": null,
              "writer": ["Ronan Bennett"],
              "genres": null,
              "genre": ["Crime", "Drama"],
              "cast": null,
              "trailers": null
            }
        """.trimIndent()
        val obj = JsonParser.parseString(rawCinemetaJson).asJsonObject
        val parsed = CatalogService().parseStremioMetaItem(obj, MediaType.SHOW)
        assertNotNull(parsed)
        assertEquals("imdb:tt31510819", parsed!!.id)
        assertEquals("MobLand", parsed.title)
        assertEquals(listOf("Ronan Bennett"), parsed.creators)
        assertEquals(listOf("Crime", "Drama"), parsed.genres)
    }

    @Test
    fun trailerParserPrefersOfficialTrailerAndBuildsCanonicalWatchUrl() {
        val json = """
            {
              "id": "tt0903747",
              "imdb_id": "tt0903747",
              "name": "Breaking Bad",
              "releaseInfo": "2008–2013",
              "trailers": [
                {"source": "clip1234567", "type": "Clip"},
                {"source": "HhesaQXLuRY", "type": "Trailer"}
              ]
            }
        """.trimIndent()
        val parsed = CatalogService().parseStremioMetaItem(JsonParser.parseString(json).asJsonObject, MediaType.SHOW)!!
        assertEquals("HhesaQXLuRY", parsed.trailerYoutubeId)
        assertEquals(
            "https://www.youtube.com/watch?v=HhesaQXLuRY",
            TrailerLauncher.buildTrailerUrl(parsed, parsed.trailerYoutubeId)
        )
    }
}
