package io.github.igrvlhlb.secrt.session

/** Why the fingerprint prompt is shown. */
enum class BiometricPurpose(internal val english: BiometricPromptText) {
    /** Turning fingerprint unlock on. */
    Enable(BiometricPromptText("Turn on fingerprint unlock", "Confirm with your fingerprint", "Cancel")),

    /** Opening the diary. */
    Unlock(BiometricPromptText("Unlock your diary", "Use your fingerprint", "Use password")),
}

/** What the system's fingerprint prompt says; [negativeButton] dismisses it. */
class BiometricPromptText(val title: String, val subtitle: String, val negativeButton: String)
