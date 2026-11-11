package io.github.igrvlhlb.secrt.ui.text

import io.github.igrvlhlb.secrt.archive.CorruptArchiveException
import io.github.igrvlhlb.secrt.archive.DifferentVaultException
import io.github.igrvlhlb.secrt.archive.NotAnArchiveException
import io.github.igrvlhlb.secrt.archive.UnsupportedArchiveException
import io.github.igrvlhlb.secrt.backup.BackupAuthException
import io.github.igrvlhlb.secrt.backup.BackupChangedException
import io.github.igrvlhlb.secrt.backup.BackupNetworkException
import io.github.igrvlhlb.secrt.backup.BackupServerException
import io.github.igrvlhlb.secrt.backup.InvalidServerAddressException
import io.github.igrvlhlb.secrt.backup.KeySlotsChangeDeclinedException
import io.github.igrvlhlb.secrt.backup.NoBackupFoundException
import io.github.igrvlhlb.secrt.backup.drive.GoogleAuthException
import io.github.igrvlhlb.secrt.backup.drive.GoogleDriveException
import io.github.igrvlhlb.secrt.crypto.DecryptionException
import io.github.igrvlhlb.secrt.lan.LanException
import io.github.igrvlhlb.secrt.lan.LanHost
import io.github.igrvlhlb.secrt.session.BiometricException
import io.github.igrvlhlb.secrt.storage.AttachmentTooLargeException
import io.github.igrvlhlb.secrt.storage.StoreClosedException
import io.github.igrvlhlb.secrt.ui.resources.*
import io.github.igrvlhlb.secrt.vault.InvalidCredentialsException
import org.jetbrains.compose.resources.StringResource

/**
 * What to tell the user about this error, in their language.
 *
 * The core modules throw typed exceptions whose messages are English, for logs and tests; their
 * text for the user lives here, as string resources picked by type and fields. An exception this
 * doesn't know shows its own message (often from the system), else [fallback].
 */
fun Throwable.toUiText(fallback: StringResource = Res.string.error_generic): UiText = toUiText(text(fallback))

/** Like the other [toUiText], with a [fallback] that has arguments. */
fun Throwable.toUiText(fallback: UiText): UiText = when (this) {
    is InvalidCredentialsException -> text(Res.string.error_wrong_credentials)
    is StoreClosedException -> text(Res.string.error_diary_locked)
    is AttachmentTooLargeException -> text(Res.string.error_attachment_too_large, (limitBytes / (1024 * 1024)).toInt())
    is DecryptionException -> text(Res.string.error_decryption)

    is NotAnArchiveException -> text(Res.string.error_not_an_archive)
    is CorruptArchiveException -> text(Res.string.error_archive_damaged, detail)
    is UnsupportedArchiveException -> text(Res.string.error_archive_newer, format)
    is DifferentVaultException -> text(Res.string.error_archive_other_diary)

    is BackupAuthException -> text(Res.string.error_backup_auth)
    is BackupNetworkException -> detail?.let { text(Res.string.error_backup_unreachable_detail, it) } ?: text(Res.string.error_backup_unreachable)
    is BackupServerException -> toUiText()
    is InvalidServerAddressException -> text(Res.string.error_backup_invalid_address)
    is BackupChangedException -> text(Res.string.error_backup_changed)
    is KeySlotsChangeDeclinedException -> text(Res.string.error_backup_kept_password)
    is NoBackupFoundException -> text(Res.string.error_backup_not_found)
    is GoogleAuthException -> toUiText()
    is GoogleDriveException -> toUiText()

    is BiometricException -> toUiText()
    is LanException -> toUiText()
    else -> message?.let(::raw) ?: fallback
}

private fun BackupServerException.toUiText(): UiText {
    val what = action?.let { action ->
        val res = when (action) {
            BackupServerException.Action.List -> Res.string.backup_action_list
            BackupServerException.Action.Download -> Res.string.backup_action_download
            BackupServerException.Action.Upload -> Res.string.backup_action_upload
            BackupServerException.Action.Delete -> Res.string.backup_action_delete
            BackupServerException.Action.CreateFolder -> Res.string.backup_action_create_folder
        }
        text(res, path.orEmpty())
    } ?: raw(path.orEmpty())
    return when (problem) {
        BackupServerException.Problem.FileTooLarge -> text(Res.string.error_backup_file_too_large, path.orEmpty())
        BackupServerException.Problem.NoWebDavFolder -> text(Res.string.error_backup_no_webdav, path.orEmpty())
        BackupServerException.Problem.NotAllowed -> text(Res.string.error_backup_not_allowed, what)
        BackupServerException.Problem.OutOfSpace -> text(Res.string.error_backup_out_of_space)
        BackupServerException.Problem.Failed -> text(Res.string.error_backup_server_failed, what, status ?: 0)
    }
}

private fun GoogleAuthException.toUiText(): UiText = when (reason) {
    GoogleAuthException.Reason.AccessRevoked -> text(Res.string.error_google_access_revoked)
    GoogleAuthException.Reason.SignInRefused -> text(Res.string.error_google_sign_in_refused, detail.orEmpty())
    GoogleAuthException.Reason.ServiceFailed -> text(Res.string.error_google_service_failed, detail.orEmpty())
    GoogleAuthException.Reason.NoAccountEmail -> detail?.let { text(Res.string.error_google_no_account_detail, it) } ?: text(Res.string.error_google_no_account)
    GoogleAuthException.Reason.DriveAccessRefused -> text(Res.string.error_google_drive_refused)
    GoogleAuthException.Reason.NotRegistered -> text(Res.string.error_google_not_registered)
    GoogleAuthException.Reason.Unreachable -> text(Res.string.error_google_unreachable)
    GoogleAuthException.Reason.Failed -> detail?.let { text(Res.string.error_google_failed_detail, it) } ?: text(Res.string.error_google_failed)
    GoogleAuthException.Reason.NotGranted -> text(Res.string.error_google_not_granted)
    GoogleAuthException.Reason.NoDriveAccess -> text(Res.string.error_google_no_drive_access)
    GoogleAuthException.Reason.AppNotOpen -> text(Res.string.error_google_app_not_open)
    GoogleAuthException.Reason.TimedOut -> text(Res.string.error_google_timed_out)
    GoogleAuthException.Reason.NoLastingAccess -> text(Res.string.error_google_no_lasting_access)
    GoogleAuthException.Reason.ReconnectOnThisComputer -> text(Res.string.error_google_reconnect_here)
    GoogleAuthException.Reason.NoBrowser -> text(Res.string.error_google_no_browser)
}

private fun GoogleDriveException.toUiText(): UiText {
    val what = when (action) {
        null -> raw(path.orEmpty())
        GoogleDriveException.Action.ListFiles -> text(Res.string.drive_action_list)
        GoogleDriveException.Action.Download -> text(Res.string.drive_action_download, path.orEmpty())
        GoogleDriveException.Action.Upload -> text(Res.string.drive_action_upload, path.orEmpty())
        GoogleDriveException.Action.Delete -> text(Res.string.drive_action_delete, path.orEmpty())
    }
    return when (problem) {
        GoogleDriveException.Problem.StorageFull -> text(Res.string.error_drive_storage_full)
        GoogleDriveException.Problem.ApiDisabled -> text(Res.string.error_drive_api_disabled)
        // Google's explanation is in English.
        GoogleDriveException.Problem.NotAllowed ->
            detail?.let { text(Res.string.error_drive_not_allowed_detail, what, it) } ?: text(Res.string.error_drive_not_allowed, what)
        GoogleDriveException.Problem.Failed -> text(Res.string.error_drive_failed, what, status ?: 0)
        GoogleDriveException.Problem.UploadNotStarted -> text(Res.string.error_drive_upload_not_started)
    }
}

private fun BiometricException.toUiText(): UiText = when (this) {
    // Not shown: the user chose to.
    is BiometricException.Cancelled -> text(Res.string.biometric_failed)
    is BiometricException.KeyInvalidated -> when (reason) {
        BiometricException.KeyInvalidated.Reason.FingerprintsChanged -> text(Res.string.biometric_fingerprints_changed)
        BiometricException.KeyInvalidated.Reason.Damaged -> text(Res.string.biometric_damaged)
        BiometricException.KeyInvalidated.Reason.OtherDiary -> text(Res.string.biometric_other_diary)
    }
    is BiometricException.Failed -> when (reason) {
        BiometricException.Failed.Reason.EnableFailed -> text(Res.string.biometric_enable_failed)
        BiometricException.Failed.Reason.NotWorking -> text(Res.string.biometric_not_working)
        BiometricException.Failed.Reason.PromptFailed -> text(Res.string.biometric_failed)
        // The system's own words, in the user's language already.
        BiometricException.Failed.Reason.System -> message?.let(::raw) ?: text(Res.string.biometric_failed)
    }
}

private fun LanException.toUiText(): UiText = when (this) {
    is LanException.Unreachable -> text(Res.string.lan_unreachable, deviceName)
    is LanException.ConnectionLost -> text(Res.string.lan_connection_lost, deviceName)
    is LanException.Refused -> text(Res.string.lan_refused)
    is LanException.WrongCode -> text(Res.string.lan_wrong_code)
    is LanException.UnknownDevice -> named(deviceName, Res.string.lan_unknown_device, Res.string.lan_unknown_device_unnamed)
    is LanException.DifferentDiary -> text(Res.string.lan_different_diary)
    is LanException.NoDiary -> text(Res.string.lan_no_diary)
    is LanException.Outdated -> named(deviceName, Res.string.lan_outdated, Res.string.lan_outdated_unnamed)
    is LanException.Declined -> when (reason) {
        LanException.Declined.Reason.NothingSent -> text(Res.string.lan_declined_nothing_sent, deviceName)
        LanException.Declined.Reason.KeepsPassword -> text(Res.string.lan_declined_keeps_password, deviceName)
    }
    is LanException.DeclinedThere -> when (reason) {
        LanException.DeclinedThere.Reason.SendDeclined ->
            named(deviceName, Res.string.lan_declined_there_send, Res.string.lan_declined_there_send_unnamed)
        LanException.DeclinedThere.Reason.NoAnswer ->
            named(deviceName, Res.string.lan_declined_there_no_answer, Res.string.lan_declined_there_no_answer_unnamed)
        LanException.DeclinedThere.Reason.KeepsPassword ->
            named(deviceName, Res.string.lan_declined_there_password, Res.string.lan_declined_there_password_unnamed)
    }
    is LanException.Locked -> text(Res.string.lan_locked)
    is LanException.Busy -> text(Res.string.lan_busy)
    is LanException.Protocol -> text(Res.string.lan_protocol, detail)
    is LanException.InvalidCode -> text(Res.string.lan_invalid_code)
}

/** Why a device connected to this one failed to pair or sync. */
fun LanHost.Event.Failed.toUiText(): UiText = when (problem) {
    LanHost.Event.Failed.Problem.NoDiaryEither -> text(Res.string.host_no_diary_either, peerName.orEmpty())
    LanHost.Event.Failed.Problem.DifferentDiary -> text(Res.string.host_different_diary, peerName.orEmpty())
    LanHost.Event.Failed.Problem.Outdated -> text(Res.string.host_outdated, peerName.orEmpty())
    LanHost.Event.Failed.Problem.ConnectionLost -> named(peerName, Res.string.host_connection_lost, Res.string.host_connection_lost_unnamed)
    LanHost.Event.Failed.Problem.Error -> cause?.toUiText(Res.string.host_sync_failed) ?: text(Res.string.host_sync_failed)
}

/** Why this device can't listen for the others. */
fun LanHost.Status.Failed.toUiText(): UiText = when (problem) {
    LanHost.Status.Failed.Problem.NoNetwork -> text(Res.string.host_no_network)
    LanHost.Status.Failed.Problem.NoPort -> text(Res.string.host_no_port)
}

private fun named(name: String?, withName: StringResource, withoutName: StringResource): UiText =
    if (name != null) text(withName, name) else text(withoutName)
