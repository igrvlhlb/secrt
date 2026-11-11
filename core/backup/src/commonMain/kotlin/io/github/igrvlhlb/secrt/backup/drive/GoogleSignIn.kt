package io.github.igrvlhlb.secrt.backup.drive

import io.github.igrvlhlb.secrt.backup.BackupException
import io.github.igrvlhlb.secrt.backup.BackupNetworkException
import io.github.igrvlhlb.secrt.backup.BackupTargetConfig
import io.github.igrvlhlb.secrt.backup.webdav.defaultHttpEngine
import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngineFactory
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.URLBuilder
import io.ktor.http.parameters
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Gets the user's consent and access tokens for Google Drive. Each app does this its own way:
 * Android through Google Play services (no secret or token stored by secrt), the desktop with the
 * OAuth flow for installed apps in the browser ([GoogleOAuthClient]).
 *
 * Failures throw a [BackupException] whose message is meant for the user.
 */
interface GoogleSignIn {
    /**
     * Shows Google's account picker and consent screen, and returns the account to back up to.
     * Throws [GoogleAuthException] if the user doesn't grant access.
     */
    suspend fun signIn(): BackupTargetConfig.GoogleDrive

    /**
     * An access token for [account]'s Drive app data. [stale] is a token Drive just refused: don't
     * return it again. May show Google's screens if the user took the access back.
     */
    suspend fun accessToken(account: BackupTargetConfig.GoogleDrive, stale: String?): String
}

/** Google turned down (or no longer gives) access to Drive. */
class GoogleAuthException(message: String) : BackupException(message)

/** Where Google's services answer; tests point these at a fake server. */
data class GoogleEndpoints(
    val accounts: String = "https://accounts.google.com",
    val oauth: String = "https://oauth2.googleapis.com",
    val api: String = "https://www.googleapis.com",
)

/**
 * What secrt asks for: its own hidden folder in Drive (not the user's files), and the account's
 * email address to show which account backups go to. Both are "non-sensitive" scopes, so Google
 * needs no app review for them.
 */
object GoogleScopes {
    const val DRIVE_APPDATA = "https://www.googleapis.com/auth/drive.appdata"
    const val EMAIL = "https://www.googleapis.com/auth/userinfo.email"
    val ALL = listOf(DRIVE_APPDATA, EMAIL)
}

@Serializable
data class GoogleTokens(
    @SerialName("access_token") val accessToken: String,
    @SerialName("expires_in") val expiresInSeconds: Long = 3600,
    /** Only when exchanging an authorization code (and not always then). */
    @SerialName("refresh_token") val refreshToken: String? = null,
)

/**
 * The OAuth 2.0 flow for installed apps with a loopback redirect and PKCE, as Google documents it
 * for desktop apps. The client secret of a "Desktop app" client isn't secret (it ships in every
 * copy of the app); PKCE is what keeps a stolen authorization code useless.
 */
class GoogleOAuthClient(
    private val clientId: String,
    private val clientSecret: String,
    private val endpoints: GoogleEndpoints = GoogleEndpoints(),
    engine: HttpClientEngineFactory<*> = defaultHttpEngine(),
) : AutoCloseable {

    private val client = googleHttpClient(engine)

    /** The page to open in the browser. [codeChallenge] is the S256 PKCE challenge. */
    fun authorizationUrl(redirectUri: String, state: String, codeChallenge: String): String =
        URLBuilder("${endpoints.accounts}/o/oauth2/v2/auth").apply {
            parameters.append("client_id", clientId)
            parameters.append("redirect_uri", redirectUri)
            parameters.append("response_type", "code")
            parameters.append("scope", GoogleScopes.ALL.joinToString(" "))
            parameters.append("state", state)
            parameters.append("code_challenge", codeChallenge)
            parameters.append("code_challenge_method", "S256")
            // Always let the user pick: they may have several Google accounts in the browser.
            parameters.append("prompt", "select_account consent")
            parameters.append("access_type", "offline")
        }.buildString()

    suspend fun exchangeCode(code: String, codeVerifier: String, redirectUri: String): GoogleTokens = token(
        "code" to code,
        "code_verifier" to codeVerifier,
        "redirect_uri" to redirectUri,
        "grant_type" to "authorization_code",
    )

    /** A new access token. Throws [GoogleAuthException] if the user took the access back. */
    suspend fun refresh(refreshToken: String): GoogleTokens = token("refresh_token" to refreshToken, "grant_type" to "refresh_token")

    /** The email address of the account [accessToken] belongs to. */
    suspend fun email(accessToken: String): String = googleAccountEmail(accessToken, client, endpoints)

    override fun close() = client.close()

    private suspend fun token(vararg fields: Pair<String, String>): GoogleTokens {
        val response = call {
            client.submitForm(
                "${endpoints.oauth}/token",
                parameters {
                    append("client_id", clientId)
                    append("client_secret", clientSecret)
                    fields.forEach { (name, value) -> append(name, value) }
                },
            )
        }
        val text = response.bodyAsText()
        return when (response.status.value) {
            200 -> lenientJson.decodeFromString(GoogleTokens.serializer(), text)
            // invalid_grant: revoked, expired, or a code already used.
            400, 401 -> throw GoogleAuthException(
                if ("invalid_grant" in text) {
                    "Google no longer gives secrt access to this account's Drive. Connect it again."
                } else {
                    "Google refused secrt's sign-in (${response.status.value})"
                },
            )
            else -> throw GoogleAuthException("Google's sign-in service failed (HTTP ${response.status.value})")
        }
    }
}

/** The email address of the account [accessToken] belongs to (needs the [GoogleScopes.EMAIL] scope). */
suspend fun googleAccountEmail(
    accessToken: String,
    endpoints: GoogleEndpoints = GoogleEndpoints(),
    engine: HttpClientEngineFactory<*> = defaultHttpEngine(),
): String = googleHttpClient(engine).use { googleAccountEmail(accessToken, it, endpoints) }

private suspend fun googleAccountEmail(accessToken: String, client: HttpClient, endpoints: GoogleEndpoints): String {
    val response = call {
        client.get("${endpoints.api}/oauth2/v3/userinfo") { header(HttpHeaders.Authorization, "Bearer $accessToken") }
    }
    if (response.status.value != 200) throw GoogleAuthException("Google didn't tell secrt which account it signed in to (HTTP ${response.status.value})")
    return lenientJson.decodeFromString(UserInfo.serializer(), response.bodyAsText()).email
        ?: throw GoogleAuthException("Google didn't tell secrt which account it signed in to")
}

@Serializable
private data class UserInfo(val email: String? = null)

internal val lenientJson = Json { ignoreUnknownKeys = true }

internal fun googleHttpClient(engine: HttpClientEngineFactory<*>) = HttpClient(engine) {
    expectSuccess = false
    install(HttpTimeout) {
        connectTimeoutMillis = 20_000
        socketTimeoutMillis = 60_000
    }
}

/** Runs a request, turning transport failures into [BackupNetworkException]. */
internal suspend fun call(request: suspend () -> HttpResponse): HttpResponse = try {
    request()
} catch (e: CancellationException) {
    throw e
} catch (e: BackupException) {
    throw e
} catch (e: Exception) {
    throw BackupNetworkException(e)
}
