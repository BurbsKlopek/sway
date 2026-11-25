package com.burbsklopek.flickdeck.data.companion

import com.burbsklopek.flickdeck.data.local.StoredCredentials
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Lightweight local LAN HTTP companion server so the user can scan a QR code on their TV
 * with their phone and paste Trakt / Simkl / TMDB API keys in 2 seconds without typing on a TV remote.
 * Only runs while the Sync Hub screen is open.
 */
class CompanionSetupServer(
    private val port: Int = 8765,
    private val getCurrentCredentials: () -> StoredCredentials,
    private val onCredentialsSubmitted: (traktId: String, traktSecret: String, simklId: String, tmdbKey: String) -> Unit
) {
    private val running = AtomicBoolean(false)
    private var serverSocket: ServerSocket? = null
    private val executor = Executors.newCachedThreadPool()

    fun start(): String? {
        if (running.get()) return buildUrl()
        return runCatching {
            val socket = ServerSocket(port)
            socket.reuseAddress = true
            serverSocket = socket
            running.set(true)
            executor.execute { acceptLoop(socket) }
            buildUrl()
        }.getOrNull()
    }

    fun stop() {
        running.set(false)
        runCatching { serverSocket?.close() }
        serverSocket = null
    }

    fun buildUrl(): String? {
        val ip = detectLanIpv4() ?: return null
        return "http://$ip:$port"
    }

    private fun acceptLoop(socket: ServerSocket) {
        while (running.get() && !socket.isClosed) {
            val client = runCatching { socket.accept() }.getOrNull() ?: break
            executor.execute { handleClient(client) }
        }
    }

    private fun handleClient(client: Socket) {
        client.use { sock ->
            sock.soTimeout = 5000
            val reader = BufferedReader(InputStreamReader(sock.getInputStream(), Charsets.UTF_8))
            val requestLine = reader.readLine() ?: return
            val parts = requestLine.split(" ")
            val method = parts.getOrNull(0).orEmpty()

            var contentLength = 0
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isEmpty()) break
                if (line.lowercase().startsWith("content-length:")) {
                    contentLength = line.substringAfter(":").trim().toIntOrNull() ?: 0
                }
            }

            var savedBanner = false
            if (method.equals("POST", ignoreCase = true) && contentLength in 1..16384) {
                val buf = CharArray(contentLength)
                var readTotal = 0
                while (readTotal < contentLength) {
                    val r = reader.read(buf, readTotal, contentLength - readTotal)
                    if (r <= 0) break
                    readTotal += r
                }
                val formRaw = String(buf, 0, readTotal)
                val params = parseFormUrlEncoded(formRaw)
                val current = getCurrentCredentials()
                onCredentialsSubmitted(
                    params["trakt_id"]?.trim() ?: current.traktClientId,
                    params["trakt_secret"]?.trim() ?: current.traktClientSecret,
                    params["simkl_id"]?.trim() ?: current.simklClientId,
                    params["tmdb_key"]?.trim() ?: current.tmdbApiKey
                )
                savedBanner = true
            }

            val html = renderCompanionHtml(getCurrentCredentials(), savedBanner)
            val bodyBytes = html.toByteArray(Charsets.UTF_8)
            val headers = buildString {
                append("HTTP/1.1 200 OK\r\n")
                append("Content-Type: text/html; charset=utf-8\r\n")
                append("Content-Length: ${bodyBytes.size}\r\n")
                append("Connection: close\r\n\r\n")
            }.toByteArray(Charsets.UTF_8)

            val out = sock.getOutputStream()
            out.write(headers)
            out.write(bodyBytes)
            out.flush()
        }
    }

    private fun parseFormUrlEncoded(body: String): Map<String, String> {
        return body.split("&").mapNotNull { pair ->
            val idx = pair.indexOf('=')
            if (idx <= 0) null
            else {
                val k = URLDecoder.decode(pair.substring(0, idx), "UTF-8")
                val v = URLDecoder.decode(pair.substring(idx + 1), "UTF-8")
                k to v
            }
        }.toMap()
    }

    private fun escapeHtml(input: String): String = input
        .replace("&", "&amp;")
        .replace("\"", "&quot;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")

    private fun renderCompanionHtml(creds: StoredCredentials, saved: Boolean): String = """
        <!DOCTYPE html>
        <html lang="en">
        <head>
          <meta charset="utf-8"/>
          <meta name="viewport" content="width=device-width, initial-scale=1"/>
          <title>Sway · Sync Companion</title>
          <style>
            * { box-sizing: border-box; font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif; }
            body {
              margin: 0; padding: 24px 16px; min-height: 100vh; color: #f8fafc;
              background: radial-gradient(circle at top right, #2e1065, #090814 65%);
            }
            .card {
              max-width: 520px; margin: 0 auto; padding: 26px;
              background: rgba(24, 20, 48, 0.78);
              border: 1px solid rgba(255, 255, 255, 0.18);
              border-radius: 24px;
              box-shadow: 0 24px 60px rgba(0,0,0,0.55);
              backdrop-filter: blur(18px);
            }
            h1 { margin: 0 0 6px; font-size: 24px; font-weight: 800; letter-spacing: -0.4px; }
            p.sub { margin: 0 0 20px; color: #94a3b8; font-size: 14px; line-height: 1.45; }
            .saved {
              padding: 12px 16px; margin-bottom: 18px; border-radius: 14px;
              background: rgba(16, 185, 129, 0.2); border: 1px solid #10b981;
              color: #6ee7b7; font-weight: 700; font-size: 14px;
            }
            .section { margin-bottom: 18px; padding-bottom: 14px; border-bottom: 1px solid rgba(255,255,255,0.08); }
            label { display: block; font-size: 12px; font-weight: 700; text-transform: uppercase; letter-spacing: 0.7px; color: #cbd5e1; margin-bottom: 6px; }
            input {
              width: 100%; padding: 13px 14px; border-radius: 12px; border: 1px solid rgba(255,255,255,0.16);
              background: rgba(9, 8, 20, 0.75); color: #fff; font-size: 14px; margin-bottom: 12px;
            }
            input:focus { outline: none; border-color: #22d3ee; }
            .hint { font-size: 12px; color: #94a3b8; margin-top: -6px; margin-bottom: 10px; }
            a { color: #22d3ee; text-decoration: none; font-weight: 600; }
            button {
              width: 100%; padding: 15px; border: none; border-radius: 14px; cursor: pointer;
              font-size: 16px; font-weight: 800; color: #fff;
              background: linear-gradient(135deg, #a855f7, #06b6d4);
              box-shadow: 0 10px 28px rgba(168, 85, 247, 0.4);
            }
          </style>
        </head>
        <body>
          <div class="card">
            <h1>Sway TV Companion</h1>
            <p class="sub">Paste your Trakt or Simkl API credentials below and tap Save. Your TV updates immediately so you can authorize with one QR scan.</p>
            ${if (saved) "<div class='saved'>✓ Sent to Sway on your TV! Select 'Connect with TV Code' on screen.</div>" else ""}
            <form method="POST" action="/">
              <div class="section">
                <label>Trakt Client ID</label>
                <input name="trakt_id" placeholder="64-char Trakt Client ID" value="${escapeHtml(creds.traktClientId)}" autocomplete="off"/>
                <label>Trakt Client Secret</label>
                <input name="trakt_secret" placeholder="64-char Trakt Client Secret" value="${escapeHtml(creds.traktClientSecret)}" autocomplete="off"/>
                <div class="hint">Create free at <a href="https://trakt.tv/oauth/applications/new" target="_blank">trakt.tv/oauth/applications</a> (Redirect URI: <code>urn:ietf:wg:oauth:2.0:oob</code>)</div>
              </div>
              <div class="section">
                <label>Simkl Client ID</label>
                <input name="simkl_id" placeholder="Simkl Client ID" value="${escapeHtml(creds.simklClientId)}" autocomplete="off"/>
                <div class="hint">Create free at <a href="https://simkl.com/settings/developer/new/" target="_blank">simkl.com/settings/developer</a></div>
              </div>
              <div class="section" style="border-bottom:none;">
                <label>Optional TMDB API Key</label>
                <input name="tmdb_key" placeholder="Optional" value="${escapeHtml(creds.tmdbApiKey)}" autocomplete="off"/>
              </div>
              <button type="submit">Save to Sway</button>
            </form>
          </div>
        </body>
        </html>
    """.trimIndent()

    companion object {
        fun detectLanIpv4(): String? {
            return runCatching {
                val interfaces = NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
                // Prefer wlan / eth interfaces over VPN tun interfaces so home LAN phones can reach the TV!
                val prioritized = interfaces.sortedBy { nif ->
                    val name = nif.name.lowercase()
                    when {
                        name.startsWith("wlan") || name.startsWith("eth") || name.startsWith("en") -> 0
                        name.startsWith("tun") || name.startsWith("nord") || name.startsWith("wg") -> 2
                        else -> 1
                    }
                }
                for (nif in prioritized) {
                    if (!nif.isUp || nif.isLoopback) continue
                    val name = nif.name.lowercase()
                    if (name.startsWith("tun") || name.startsWith("nord")) continue
                    for (addr in nif.inetAddresses) {
                        if (!addr.isLoopbackAddress && addr is Inet4Address && addr.isSiteLocalAddress) {
                            return@runCatching addr.hostAddress
                        }
                    }
                }
                null
            }.getOrNull()
        }
    }
}
