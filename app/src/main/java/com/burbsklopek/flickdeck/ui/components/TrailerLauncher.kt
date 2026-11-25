package com.burbsklopek.flickdeck.ui.components

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import com.burbsklopek.flickdeck.model.MediaItem
import com.burbsklopek.flickdeck.model.VideoQuality

object TrailerLauncher {
    internal fun buildTrailerUrl(item: MediaItem, resolvedYoutubeId: String?): String {
        val ytId = resolvedYoutubeId?.trim()?.takeIf { Regex("^[A-Za-z0-9_-]{11}$").matches(it) }
        return if (ytId != null) {
            // Canonical YouTube watch URL required by Cobalt/TizenTube/YouTube TV deep links
            "https://www.youtube.com/watch?v=$ytId"
        } else {
            val yearHint = Regex("(19|20)\\d{2}").find(item.year)?.value.orEmpty()
            val query = Uri.encode(
                listOf(item.title, yearHint, "official trailer").filter { it.isNotBlank() }.joinToString(" ")
            )
            // YouTube TV / Cobalt search deep link (desktop /results?search_query= lands on home in Cobalt)
            "https://www.youtube.com/tv#/search?q=$query"
        }
    }

    /**
     * Launches the show's trailer on Android TV requesting the configured video quality
     * (defaults strictly to 2160p / 4K UHD). Works seamlessly with TizenTube Cobalt,
     * SmartTube, YouTube for Android TV, or any installed browser/video handler.
     */
    fun launchTrailer(
        context: Context,
        item: MediaItem,
        quality: VideoQuality,
        resolvedYoutubeId: String? = item.trailerYoutubeId
    ) {
        val url = buildTrailerUrl(item, resolvedYoutubeId)
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            putExtra("preferred_video_quality", quality.heightPx)
            putExtra("vq", quality.youtubeVq)
            putExtra("force_fullscreen", true)
        }

        runCatching {
            context.startActivity(intent)
        }.onFailure {
            Toast.makeText(
                context,
                "No YouTube/4K video player installed to open trailer",
                Toast.LENGTH_SHORT
            ).show()
        }
    }
}
