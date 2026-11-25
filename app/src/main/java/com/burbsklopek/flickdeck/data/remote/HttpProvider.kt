package com.burbsklopek.flickdeck.data.remote

import com.burbsklopek.flickdeck.BuildConfig
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

fun JsonObject.optObj(key: String): JsonObject? =
    get(key)?.takeIf { it.isJsonObject }?.asJsonObject

fun JsonObject.optArr(key: String): JsonArray? =
    get(key)?.takeIf { it.isJsonArray }?.asJsonArray

fun JsonObject.optStr(key: String): String? =
    get(key)?.takeIf { it.isJsonPrimitive }?.asString

object HttpProvider {
    const val USER_AGENT = "Sway/${BuildConfig.VERSION_NAME} (Android TV 12; 4K)"

    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(16, TimeUnit.SECONDS)
            .writeTimeout(16, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .addInterceptor { chain ->
                val req = chain.request().newBuilder()
                    .header("User-Agent", USER_AGENT)
                    .build()
                chain.proceed(req)
            }
            .build()
    }

    fun metahubPoster(imdbId: String?): String =
        if (!imdbId.isNullOrBlank()) "https://images.metahub.space/poster/large/$imdbId/img" else ""

    fun metahubBackdrop(imdbId: String?): String =
        if (!imdbId.isNullOrBlank()) "https://images.metahub.space/background/large/$imdbId/img" else ""

    fun metahubLogo(imdbId: String?): String? =
        if (!imdbId.isNullOrBlank()) "https://images.metahub.space/logo/medium/$imdbId/img" else null
}
