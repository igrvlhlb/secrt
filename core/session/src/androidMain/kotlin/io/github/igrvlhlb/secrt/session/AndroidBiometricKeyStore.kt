package io.github.igrvlhlb.secrt.session

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import android.util.Log
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.lang.ref.WeakReference
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.security.ProviderException
import java.security.UnrecoverableKeyException
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Biometric unlock on Android: the data key, encrypted with an AES-256-GCM key that lives in the
 * Android Keystore, in a small file in the app's private storage ([file]).
 *
 * The Keystore key:
 * - can't leave the secure hardware (StrongBox when the device has it, otherwise the TEE);
 * - requires authentication for *every* use, granted only by a [BiometricPrompt] bound to that
 *   one operation through a [BiometricPrompt.CryptoObject], and only by Class 3 (strong)
 *   biometrics. Weak ones like most face unlocks can't unlock it, so they are never offered;
 * - is invalidated by the system when a new fingerprint is enrolled, or all are removed, or the
 *   screen lock is removed. A new fingerprint can then never open the diary without the password.
 *
 * So the file alone is useless, and so is the phone without the enrolled finger. Requires
 * Android 9: below that the support library falls back to a dialog that needs an AppCompat theme.
 *
 * Prompts are shown over the activity given to [attach], with the texts [promptText] gives (the
 * app passes them in the user's language: this module has no translations).
 */
class AndroidBiometricKeyStore(
    private val context: Context,
    private val file: File = File(context.filesDir, "biometric.key"),
    private val promptText: (Context, BiometricPurpose) -> BiometricPromptText = { _, purpose -> purpose.english },
) : BiometricKeyStore {

    private var activity = WeakReference<FragmentActivity>(null)

    /** Sets the activity prompts are shown over. Call from its `onCreate`. */
    fun attach(activity: FragmentActivity) {
        this.activity = WeakReference(activity)
    }

    override fun availability(): BiometricAvailability {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return BiometricAvailability.Unsupported
        return when (BiometricManager.from(context).canAuthenticate(BIOMETRIC_STRONG)) {
            BiometricManager.BIOMETRIC_SUCCESS -> BiometricAvailability.Available
            BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> BiometricAvailability.NoneEnrolled
            else -> BiometricAvailability.Unsupported
        }
    }

    override fun hasKey(): Boolean = file.exists()

    override suspend fun store(dataKey: ByteArray) {
        try {
            val cipher = withContext(Dispatchers.IO) {
                delete()
                Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, generateKey(strongBox = true)) }
            }
            val authorized = authenticate(cipher, BiometricPurpose.Enable)
            val iv = authorized.iv
            // Only after the prompt: the Keystore refuses every step of the operation, AAD included, until then.
            authorized.updateAAD(AAD)
            val wrapped = authorized.doFinal(dataKey)
            withContext(Dispatchers.IO) { write(iv, wrapped) }
        } catch (e: Exception) {
            withContext(NonCancellable + Dispatchers.IO) { delete() }
            if (e is BiometricException || e is CancellationException) throw e
            Log.w(TAG, "Couldn't turn on biometric unlock", e)
            throw BiometricException.Failed(BiometricException.Failed.Reason.EnableFailed)
        }
    }

    override suspend fun retrieve(): ByteArray {
        val (iv, wrapped) = withContext(Dispatchers.IO) { read() } ?: throw invalidated()
        val cipher = withContext(Dispatchers.IO) {
            try {
                val key = keyStore().getKey(KEY_ALIAS, null) as? SecretKey ?: throw invalidated()
                Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv)) }
            } catch (e: KeyPermanentlyInvalidatedException) {
                throw invalidated()
            } catch (e: UnrecoverableKeyException) {
                throw invalidated()
            } catch (e: GeneralSecurityException) {
                Log.w(TAG, "Couldn't load the biometric key", e)
                throw BiometricException.Failed(BiometricException.Failed.Reason.NotWorking)
            }
        }
        val authorized = authenticate(cipher, BiometricPurpose.Unlock)
        return try {
            authorized.updateAAD(AAD)
            authorized.doFinal(wrapped)
        } catch (e: AEADBadTagException) {
            // The file was damaged or doesn't match the key: nothing will ever decrypt it.
            throw BiometricException.KeyInvalidated(BiometricException.KeyInvalidated.Reason.Damaged)
        } catch (e: Exception) { // GeneralSecurityException, or the Keystore's ProviderException
            Log.w(TAG, "Couldn't decrypt with the biometric key", e)
            throw BiometricException.Failed(BiometricException.Failed.Reason.NotWorking)
        }
    }

    override fun delete() {
        file.delete()
        try {
            keyStore().deleteEntry(KEY_ALIAS)
        } catch (e: GeneralSecurityException) {
            Log.w(TAG, "Couldn't delete the biometric key", e)
        }
    }

    private fun invalidated() = BiometricException.KeyInvalidated(BiometricException.KeyInvalidated.Reason.FingerprintsChanged)

    private fun generateKey(strongBox: Boolean): SecretKey {
        val spec = KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setUserAuthenticationRequired(true)
            .setInvalidatedByBiometricEnrollment(true)
            .apply {
                // Timeout 0 = authorize each operation through a CryptoObject. Before Android 11 that
                // is the default, and only biometrics can authorize such keys.
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG)
                }
                if (strongBox && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) setIsStrongBoxBacked(true)
            }
            .build()
        return try {
            KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE).run {
                init(spec)
                generateKey()
            }.also { Log.i(TAG, "Generated the biometric key (StrongBox: $strongBox)") }
        } catch (e: ProviderException) {
            // StrongBoxUnavailableException, or a StrongBox that can't make this key: use the TEE.
            if (!strongBox) throw e
            generateKey(strongBox = false)
        }
    }

    private suspend fun authenticate(cipher: Cipher, purpose: BiometricPurpose): Cipher =
        withContext(Dispatchers.Main) {
            val host = activity.get()
                ?.takeUnless { it.isFinishing || it.isDestroyed || it.supportFragmentManager.isStateSaved }
                // Not in front: nothing to show the prompt over. The user can retry from the lock screen.
                ?: throw BiometricException.Cancelled()
            suspendCancellableCoroutine { continuation ->
                val prompt = BiometricPrompt(
                    host,
                    ContextCompat.getMainExecutor(host),
                    object : BiometricPrompt.AuthenticationCallback() {
                        override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                            val authorized = result.cryptoObject?.cipher
                            if (authorized != null) {
                                continuation.resume(authorized)
                            } else {
                                continuation.resumeWithException(BiometricException.Failed(BiometricException.Failed.Reason.PromptFailed))
                            }
                        }

                        override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                            continuation.resumeWithException(errorFor(errorCode, errString))
                        }

                        // onAuthenticationFailed (a finger that doesn't match): the prompt stays up and says so.
                    },
                )
                val text = promptText(host, purpose)
                val info = BiometricPrompt.PromptInfo.Builder()
                    .setTitle(text.title)
                    .setSubtitle(text.subtitle)
                    .setNegativeButtonText(text.negativeButton)
                    .setAllowedAuthenticators(BIOMETRIC_STRONG)
                    .setConfirmationRequired(false)
                    .build()
                prompt.authenticate(info, BiometricPrompt.CryptoObject(cipher))
                continuation.invokeOnCancellation { prompt.cancelAuthentication() }
            }
        }

    private fun errorFor(code: Int, message: CharSequence): BiometricException = when (code) {
        BiometricPrompt.ERROR_USER_CANCELED,
        BiometricPrompt.ERROR_NEGATIVE_BUTTON,
        BiometricPrompt.ERROR_CANCELED,
        -> BiometricException.Cancelled()
        BiometricPrompt.ERROR_NO_BIOMETRICS -> invalidated()
        else -> BiometricException.Failed(BiometricException.Failed.Reason.System, message.toString())
    }

    private fun keyStore(): KeyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }

    /** `version || iv length || iv || ciphertext`, replaced atomically. */
    private fun write(iv: ByteArray, wrapped: ByteArray) {
        val temp = File(file.parentFile, "${file.name}.tmp")
        temp.writeBytes(byteArrayOf(FILE_VERSION, iv.size.toByte()) + iv + wrapped)
        if (!temp.renameTo(file)) {
            temp.delete()
            error("Couldn't save the biometric key")
        }
    }

    private fun read(): Pair<ByteArray, ByteArray>? {
        val bytes = file.takeIf { it.exists() }?.readBytes() ?: return null
        if (bytes.size < 2 || bytes[0] != FILE_VERSION) return null
        val ivEnd = 2 + bytes[1]
        if (bytes.size <= ivEnd) return null
        return bytes.copyOfRange(2, ivEnd) to bytes.copyOfRange(ivEnd, bytes.size)
    }

    private companion object {
        const val TAG = "secrt"
        const val KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "secrt-biometric-unlock"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val GCM_TAG_BITS = 128
        const val FILE_VERSION: Byte = 1

        /** Binds the ciphertext to its purpose. */
        val AAD = "secrt-biometric-data-key-v1".encodeToByteArray()
    }
}
