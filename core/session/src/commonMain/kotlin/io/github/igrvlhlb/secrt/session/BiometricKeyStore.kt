package io.github.igrvlhlb.secrt.session

enum class BiometricAvailability {
    /** Strong biometrics are set up: the feature can be turned on. */
    Available,

    /** The hardware is there, but no fingerprint is enrolled in the system settings. */
    NoneEnrolled,

    /** No strong (Class 3) biometrics on this device or platform: don't offer the feature. */
    Unsupported,
}

/**
 * Why biometric unlock didn't produce a key. The diary can always be opened with the password.
 * Messages are in English, for logs and tests; the UI shows its own (translated) text for each reason.
 */
sealed class BiometricException(message: String) : Exception(message) {
    /** The user dismissed the prompt or chose the password instead. Not worth a message. */
    class Cancelled : BiometricException("Cancelled")

    /**
     * The stored key can never be used again (new fingerprint enrolled, biometrics or the screen
     * lock removed, or it doesn't match this diary). The feature has been turned off.
     */
    class KeyInvalidated(val reason: Reason) : BiometricException(reason.english) {
        enum class Reason(internal val english: String) {
            FingerprintsChanged("Your fingerprints changed, so fingerprint unlock was turned off. Use your password, then turn it on again in Settings."),
            Damaged("Fingerprint unlock stopped working and was turned off. Use your password."),
            OtherDiary("The saved fingerprint key doesn't belong to this diary"),
        }
    }

    /**
     * Anything else, e.g. too many attempts. For [Reason.System], [message] is the system's own
     * explanation, already in the user's language.
     */
    class Failed(val reason: Reason, systemMessage: String? = null) : BiometricException(systemMessage ?: reason.english) {
        enum class Reason(internal val english: String) {
            EnableFailed("Couldn't turn on fingerprint unlock"),
            NotWorking("Fingerprint unlock isn't working right now. Use your password."),
            PromptFailed("Fingerprint unlock failed"),
            System("Fingerprint unlock failed"),
        }
    }
}

/**
 * Keeps the vault's data key behind strong biometric authentication (on Android: wrapped by a
 * Keystore key that only a fingerprint can unlock). Implementations must never write the data
 * key in clear, and each operation shows the platform's prompt, so call them from the UI's
 * coroutine scope, not while holding locks.
 *
 * The data key, not the password, is what gets stored: the password then never has to be kept
 * anywhere, unlocking skips Argon2id, and since password changes don't touch the data key,
 * biometric unlock survives them.
 */
interface BiometricKeyStore {
    fun availability(): BiometricAvailability

    /** Whether a wrapped key is stored, i.e. the feature is on. Works while locked. */
    fun hasKey(): Boolean

    /**
     * Asks the user to authenticate, then stores [dataKey] wrapped, replacing any previous one.
     * The caller wipes [dataKey] afterwards.
     *
     * @throws BiometricException; nothing is stored then.
     */
    suspend fun store(dataKey: ByteArray)

    /**
     * Asks the user to authenticate and returns the data key. The caller wipes it.
     *
     * @throws BiometricException
     */
    suspend fun retrieve(): ByteArray

    /** Deletes the wrapped key and the hardware key protecting it. */
    fun delete()
}
