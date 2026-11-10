package io.github.igrvlhlb.secrt.android

import android.accounts.Account
import android.app.Activity
import android.content.Context
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResult
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.ClearTokenRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.CommonStatusCodes
import com.google.android.gms.common.api.Scope
import io.github.igrvlhlb.secrt.backup.BackupTargetConfig
import io.github.igrvlhlb.secrt.backup.drive.GoogleAuthException
import io.github.igrvlhlb.secrt.backup.drive.GoogleScopes
import io.github.igrvlhlb.secrt.backup.drive.GoogleSignIn
import io.github.igrvlhlb.secrt.backup.drive.googleAccountEmail
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

/**
 * Google Drive on Android, through Google Play services' AuthorizationClient, as Google
 * recommends: Play services keeps the grant and hands out short-lived access tokens, so secrt
 * stores no token (the target's settings hold only the account's email address). Google matches
 * the app to its OAuth client by package name and signing certificate, so no client ID ships in it.
 *
 * Google's screens (account picker, consent) are activities: [attach] each new [MainActivity] so
 * they can be shown from it.
 */
class AndroidGoogleSignIn(private val context: Context) : GoogleSignIn {

    private var launcher: ActivityResultLauncher<IntentSenderRequest>? = null
    private var pending: CompletableDeferred<ActivityResult>? = null

    /** Registers the launcher for Google's screens; call from the activity's `onCreate`. */
    fun attach(activity: ComponentActivity) {
        // Lives here, not in the activity, so a result still arrives after a rotation.
        launcher = activity.registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { pending?.complete(it) }
    }

    override suspend fun signIn(): BackupTargetConfig.GoogleDrive {
        val token = authorize(account = null)
        return BackupTargetConfig.GoogleDrive(googleAccountEmail(token))
    }

    override suspend fun accessToken(account: BackupTargetConfig.GoogleDrive, stale: String?): String {
        if (stale != null) {
            // Play services caches tokens: drop the refused one, or it would hand it out again.
            client().clearToken(ClearTokenRequest.builder().setToken(stale).build()).await()
        }
        return authorize(Account(account.account, "com.google"))
    }

    /** A token for [account] (or one the user picks), showing Google's screens if access isn't granted yet. */
    private suspend fun authorize(account: Account?): String {
        val request = AuthorizationRequest.builder()
            .setRequestedScopes(GoogleScopes.ALL.map(::Scope))
            .apply { account?.let(::setAccount) }
            .build()
        val result = try {
            val first = client().authorize(request).await()
            if (first.hasResolution()) resolve(first) else first
        } catch (e: ApiException) {
            throw GoogleAuthException(
                when (e.statusCode) {
                    CommonStatusCodes.DEVELOPER_ERROR -> "This build of secrt isn't registered with Google (developer error)"
                    CommonStatusCodes.NETWORK_ERROR -> "Couldn't reach Google"
                    else -> "Google sign-in failed (${CommonStatusCodes.getStatusCodeString(e.statusCode)})"
                },
            )
        }
        val granted = result.grantedScopes.map { it.toString() }
        if (GoogleScopes.DRIVE_APPDATA !in granted) throw GoogleAuthException("Google Drive access wasn't granted")
        return result.accessToken ?: throw GoogleAuthException("Google didn't give secrt access to Drive")
    }

    /** Shows Google's account picker and consent screen, and waits for the user. */
    private suspend fun resolve(result: AuthorizationResult): AuthorizationResult {
        val launcher = launcher ?: throw GoogleAuthException("Open secrt to sign in to Google")
        val intentSender = result.pendingIntent?.intentSender ?: throw GoogleAuthException("Google sign-in failed")
        val answer = CompletableDeferred<ActivityResult>()
        pending = answer
        try {
            withContext(Dispatchers.Main) { launcher.launch(IntentSenderRequest.Builder(intentSender).build()) }
            val activityResult = answer.await()
            if (activityResult.resultCode != Activity.RESULT_OK) throw GoogleAuthException("Google Drive access wasn't granted")
            return client().getAuthorizationResultFromIntent(activityResult.data)
        } finally {
            pending = null
        }
    }

    private fun client() = Identity.getAuthorizationClient(context)
}
