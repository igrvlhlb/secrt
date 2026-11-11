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

/**
 * Google turned down (or no longer gives) access to Drive, for [reason]. [detail] is technical
 * (an HTTP status, or Google's error code). The message is English, for logs and tests; the UI
 * has its own text for each reason.
 */
class GoogleAuthException(val reason: Reason, val detail: String? = null) : BackupException(englishMessage(reason, detail)) {

    enum class Reason {
        /** The user took the access back (or it expired): connect again. */
        AccessRevoked,
        SignInRefused,
        ServiceFailed,
        NoAccountEmail,
        /** Drive refused even a fresh token. */
        DriveAccessRefused,
        /** This build's OAuth client isn't registered with Google. */
        NotRegistered,
        Unreachable,
        Failed,
        NotGranted,
        NoDriveAccess,
        /** Google's screens need the app in front. */
        AppNotOpen,
        TimedOut,
        NoLastingAccess,
        /** This computer has no refresh token for the account (e.g. a backup set up on another device). */
        ReconnectOnThisComputer,
        NoBrowser,
    }

    private companion object {
        fun englishMessage(reason: Reason, detail: String?): String = when (reason) {
            Reason.AccessRevoked -> "Google no longer gives secrt access to this account's Drive. Connect it again."
            Reason.SignInRefused -> "Google refused secrt's sign-in ($detail)"
            Reason.ServiceFailed -> "Google's sign-in service failed (HTTP $detail)"
            Reason.NoAccountEmail -> "Google didn't tell secrt which account it signed in to" + (detail?.let { " (HTTP $it)" } ?: "")
            Reason.DriveAccessRefused -> "Google refused secrt access to your Drive. Connect your Google account again."
            Reason.NotRegistered -> "This build of secrt isn't registered with Google (developer error)"
            Reason.Unreachable -> "Couldn't reach Google"
            Reason.Failed -> "Google sign-in failed" + (detail?.let { " ($it)" } ?: "")
            Reason.NotGranted -> "Google Drive access wasn't granted"
            Reason.NoDriveAccess -> "Google didn't give secrt access to Drive"
            Reason.AppNotOpen -> "Open secrt to sign in to Google"
            Reason.TimedOut -> "Google sign-in took too long. Try again."
            Reason.NoLastingAccess -> "Google didn't give secrt lasting access. Try again."
            Reason.ReconnectOnThisComputer -> "Connect your Google account again on this computer"
            Reason.NoBrowser -> "secrt couldn't open a browser for Google sign-in"
        }
    }
}

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
            400, 401 -> throw if ("invalid_grant" in text) {
                GoogleAuthException(GoogleAuthException.Reason.AccessRevoked)
            } else {
                GoogleAuthException(GoogleAuthException.Reason.SignInRefused, response.status.value.toString())
            }
            else -> throw GoogleAuthException(GoogleAuthException.Reason.ServiceFailed, response.status.value.toString())
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
    if (response.status.value != 200) throw GoogleAuthException(GoogleAuthException.Reason.NoAccountEmail, response.status.value.toString())
    return lenientJson.decodeFromString(UserInfo.serializer(), response.bodyAsText()).email
        ?: throw GoogleAuthException(GoogleAuthException.Reason.NoAccountEmail)
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
