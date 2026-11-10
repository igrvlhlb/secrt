package io.github.igrvlhlb.secrt.backup

import io.github.igrvlhlb.secrt.backup.drive.GoogleDriveTarget
import io.github.igrvlhlb.secrt.backup.drive.GoogleSignIn
import io.github.igrvlhlb.secrt.backup.webdav.WebDavTarget
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * How to reach a [BackupTarget], credentials included. Kept only inside the encrypted database
 * (see [CloudBackup.saveTarget]), and asked for again when restoring on a new device.
 */
@Serializable
sealed class BackupTargetConfig {
    /** Short, non-secret description for the UI, e.g. `alice @ cloud.example.com`. */
    abstract val description: String

    /** [google] gets access tokens for a [GoogleDrive] target; other targets ignore it. */
    abstract fun open(google: GoogleSignIn? = null): BackupTarget

    /**
     * A WebDAV folder (Nextcloud, a NAS, any WebDAV server) with Basic auth. [url] is the folder
     * itself, ending in `/`; for Nextcloud that is `https://host/remote.php/dav/files/<user>/`.
     * Use an app password where the server offers them.
     */
    @Serializable
    @SerialName("webdav")
    data class WebDav(val url: String, val username: String, val password: String) : BackupTargetConfig() {
        override val description: String
            get() = "$username @ " + url.substringAfter("://").substringBefore('/')

        /** Plain `http://` sends the password and the (encrypted) data unprotected by TLS. */
        val isEncryptedTransport: Boolean get() = url.startsWith("https://", ignoreCase = true)

        override fun open(google: GoogleSignIn?): BackupTarget = WebDavTarget(this)

        // Never print the password (logs, crash reports).
        override fun toString() = "WebDav(url=$url, username=$username)"
    }

    /**
     * The hidden app data folder of [account]'s Google Drive, as WhatsApp uses: only secrt can see
     * it, and it counts towards the account's storage. [refreshToken] is kept by apps that do the
     * OAuth flow themselves (the desktop); Android gets tokens from Google Play services instead.
     */
    @Serializable
    @SerialName("gdrive")
    data class GoogleDrive(val account: String, val refreshToken: String? = null) : BackupTargetConfig() {
        override val description: String
            get() = "Google Drive · $account"

        override fun open(google: GoogleSignIn?): BackupTarget =
            GoogleDriveTarget(this, google ?: throw BackupException("This version of secrt can't reach Google Drive"))

        // Never print the token.
        override fun toString() = "GoogleDrive(account=$account)"
    }
}
