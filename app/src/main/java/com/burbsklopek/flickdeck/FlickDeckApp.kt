package com.burbsklopek.flickdeck

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
import com.burbsklopek.flickdeck.data.remote.HttpProvider

class FlickDeckApp : Application(), ImageLoaderFactory {
    override fun newImageLoader(): ImageLoader {
        return ImageLoader.Builder(this)
            .okHttpClient(HttpProvider.client)
            .memoryCache {
                MemoryCache.Builder(this)
                    .maxSizePercent(0.25)
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir.resolve("flickdeck_artwork_cache"))
                    .maxSizeBytes(160L * 1024L * 1024L)
                    .build()
            }
            .respectCacheHeaders(false)
            .build()
    }
}
