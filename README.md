# Sway

A **4K 60fps Glassmorphic Swipe-Deck Watch Tracker** built natively for **Android TV** (`com.burbsklopek.sway`), pairing a hardware-accelerated 3D swipe card deck with bidirectional **Trakt** and **Simkl** cloud sync.

## Features

- **60fps GPU-Accelerated 3D Swipe Deck**:
  - Pure `graphicsLayer` spring physics (`stiffness = 720f`) with zero layout thrashing and automatic Coil RAM/Disk prefetching of the next 5 cards.
  - **TV Remote Swipe Presets**:
    - **Three-Way Remote (Default)**: `→` **To Watch (Watchlist)** · `↑` **Watched ✓** · `←` **Pass** · `↓` **Undo Rewind** · `OK` **Details & Trailer** · `Back` **Focus Filters/Menu**.
    - **Direct Watched / To-Watch**: `→` **Watched** · `←` **To Watch** · `↓` **Pass**.
    - **Action Button Dock**: Classic D-Pad focus across interactive bottom action pills.
  - Also supports touch / air-mouse drag gestures with live tilt and glowing neon swipe stamps.
- **Native Trakt & Simkl Cloud Sync**:
  - **Trakt API v2**: OAuth2 Device Code + QR activation (`trakt.tv/activate`), automatic token refresh, bidirectional `/sync/watchlist` & `/sync/history` + user ratings.
  - **Simkl API (Auth V2 RFC 8628 + V1 PIN Fallback)**: Device PIN + QR activation (`simkl.com/pin`), bidirectional `/sync/all-items` & `/sync/add-to-list` (`plantowatch` / `completed`) across TV Shows, Anime, and Movies.
  - **Offline-First Sync Queue**: Swipes apply in `0ms` locally and flush asynchronously in the background.
  - **LAN Phone Companion QR Setup (`http://<tv-ip>:8765`)**: Scan a QR code on the TV to paste Trakt/Simkl API credentials from your phone clipboard in seconds (smartly skips VPN tunnel interfaces so home Wi-Fi phones connect directly).
- **Zero-Key Instant Discovery**:
  - Works out of the box on first launch via Cinemeta (`IMDb` high-res posters, 4K fanart backdrops, awards, cast, trailers) and Jikan (`Anime`), blended with personalized Trakt & Simkl recommendations when connected.
- **Liquid-Glass & 4K Customization**:
  - **6 Dual-Tone Glass Themes**: `Midnight Nebula`, `Crimson Velvet`, `Cyber Matcha`, `Obsidian Gold`, `Arctic Aurora`, `Y2K Chrome`.
  - Hardware `RenderEffect` 4K backdrop blur on Android TV 12+, adjustable glass crystal intensity, card glow shadows, and Ultra HD trailer playback.
  - **Support / Donate**: Built-in **Buy Me a Coffee** (`https://buymeacoffee.com/burbsklopek`) glass card and 4K vector QR modal in Settings.

## Build

```bash
bash scripts/build-apk.sh
```

Outputs the signed release APK to `~/Downloads/Android Development/Sway-v1.0.0.apk`.
