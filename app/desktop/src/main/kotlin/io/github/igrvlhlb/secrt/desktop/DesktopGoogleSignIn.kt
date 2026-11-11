package io.github.igrvlhlb.secrt.desktop

import io.github.igrvlhlb.secrt.backup.BackupTargetConfig
import io.github.igrvlhlb.secrt.backup.drive.GoogleAuthException
import io.github.igrvlhlb.secrt.backup.drive.GoogleOAuthClient
import io.github.igrvlhlb.secrt.backup.drive.GoogleSignIn
import io.github.igrvlhlb.secrt.ui.DesktopTexts
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.awt.Desktop
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URI
import java.net.URLDecoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.Properties
import java.util.concurrent.ConcurrentHashMap

/**
 * The Google OAuth client this build was made with (see `local.properties` in docs/architecture.md),
 * or `null` if there is none: then Google Drive isn't offered.
 */
fun desktopGoogleSignIn(): GoogleSignIn? {
    val properties = Properties()
    DesktopGoogleSignIn::class.java.getResourceAsStream("/secrt-google.properties")?.use(properties::load)
    val clientId = properties.getProperty("desktopClientId")?.takeIf { it.isNotBlank() } ?: return null
    val clientSecret = properties.getProperty("desktopClientSecret")?.takeIf { it.isNotBlank() } ?: return null
    return DesktopGoogleSignIn(GoogleOAuthClient(clientId, clientSecret))
}

/**
 * Google Drive on the desktop, with Google's OAuth flow for installed apps: the browser opens
 * Google's consent page, which sends the browser back to a one-off server on 127.0.0.1 with an
 * authorization code. The refresh token it gives is kept in the target's settings, inside the
 * encrypted database; access tokens only in memory.
 */
class DesktopGoogleSignIn(private val oauth: GoogleOAuthClient) : GoogleSignIn {

    /** Refresh token → access token and when it expires (epoch millis). */
    private val accessTokens = ConcurrentHashMap<String, Pair<String, Long>>()

    override suspend fun signIn(): BackupTargetConfig.GoogleDrive = withContext(Dispatchers.IO) {
        ServerSocket(0, 50, InetAddress.getByName("127.0.0.1")).use { server ->
            server.soTimeout = 500
            val redirectUri = "http://127.0.0.1:${server.localPort}"
            val verifier = randomToken(32)
            val challenge = Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray()))
            val state = randomToken(16)
            openInBrowser(oauth.authorizationUrl(redirectUri, state, challenge))
            val code = withTimeoutOrNull(SIGN_IN_TIMEOUT_MILLIS) { awaitCode(server, state) }
                ?: throw GoogleAuthException(GoogleAuthException.Reason.TimedOut)
            val tokens = oauth.exchangeCode(code, verifier, redirectUri)
            val refreshToken = tokens.refreshToken ?: throw GoogleAuthException(GoogleAuthException.Reason.NoLastingAccess)
            accessTokens[refreshToken] = tokens.accessToken to expiry(tokens.expiresInSeconds)
            BackupTargetConfig.GoogleDrive(oauth.email(tokens.accessToken), refreshToken)
        }
    }

    override suspend fun accessToken(account: BackupTargetConfig.GoogleDrive, stale: String?): String {
        val refreshToken = account.refreshToken ?: throw GoogleAuthException(GoogleAuthException.Reason.ReconnectOnThisComputer)
        accessTokens[refreshToken]?.let { (token, expiresAt) ->
            if (token != stale && System.currentTimeMillis() < expiresAt) return token
        }
        val tokens = oauth.refresh(refreshToken)
        accessTokens[refreshToken] = tokens.accessToken to expiry(tokens.expiresInSeconds)
        return tokens.accessToken
    }

    /** Answers the browser's requests until Google's redirect with our [state] arrives. */
    private suspend fun awaitCode(server: ServerSocket, state: String): String {
        while (true) {
            currentCoroutineContext().ensureActive()
            val socket = try {
                server.accept()
            } catch (e: SocketTimeoutException) {
                continue
            }
            socket.use {
                it.soTimeout = 5_000
                val target = BufferedReader(InputStreamReader(it.getInputStream(), Charsets.US_ASCII)).readLine()?.split(' ')?.getOrNull(1)
                val query = target?.substringAfter('?', "").orEmpty().split('&').filter { p -> '=' in p }.associate { p ->
                    URLDecoder.decode(p.substringBefore('='), Charsets.UTF_8) to URLDecoder.decode(p.substringAfter('='), Charsets.UTF_8)
                }
                // The browser may ask for other things first (a favicon); a wrong state isn't from our request.
                if (query["state"] != state) {
                    it.respond(404, "Not found")
                    continue
                }
                val error = query["error"]
                val code = query["code"]
                if (code == null || error != null) {
                    it.respond(200, page(DesktopTexts.googleSignInDenied()))
                    throw if (error == "access_denied") {
                        GoogleAuthException(GoogleAuthException.Reason.NotGranted)
                    } else {
                        GoogleAuthException(GoogleAuthException.Reason.Failed, error)
                    }
                }
                it.respond(200, page(DesktopTexts.googleSignInDone()))
                return code
            }
        }
    }

    private fun Socket.respond(status: Int, body: String) {
        val bytes = body.toByteArray()
        val reason = if (status == 200) "OK" else "Not Found"
        getOutputStream().apply {
            write("HTTP/1.1 $status $reason\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
            write(bytes)
            flush()
        }
    }

    private fun page(message: String) =
        """<!doctype html><html><head><meta charset="utf-8"><title>secrt</title></head>""" +
            """<body style="font-family:system-ui,sans-serif;max-width:32em;margin:4em auto;padding:0 1em"><h1>secrt</h1><p>$message</p></body></html>"""

    private fun openInBrowser(url: String) {
        if (!Desktop.isDesktopSupported() || !Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
            throw GoogleAuthException(GoogleAuthException.Reason.NoBrowser)
        }
        Desktop.getDesktop().browse(URI(url))
    }

    private fun expiry(expiresInSeconds: Long) = System.currentTimeMillis() + (expiresInSeconds - 60).coerceAtLeast(0) * 1000

    private fun randomToken(bytes: Int): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(bytes).also(SecureRandom()::nextBytes))

    private companion object {
        const val SIGN_IN_TIMEOUT_MILLIS = 5 * 60 * 1000L
    }
}
