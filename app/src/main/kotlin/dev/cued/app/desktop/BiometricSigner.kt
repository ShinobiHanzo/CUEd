package dev.cued.app.desktop

import android.app.Activity
import android.content.Context
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.os.Build
import android.os.CancellationSignal
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import dev.cued.core.desktop.Assertion
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

/**
 * The phone's biometric key (CUEd-desktop `docs/protocol.md` §7): an ECDSA
 * P-256 key in the Android Keystore that only signs after the person unlocks
 * with a fingerprint, face or device credential. The desktop keeps the
 * public half from pairing and asks for a fresh signature every few minutes
 * while the phone reads from outside the LAN. No biometric data is ever
 * seen by the app or the desktop: only a signature from a key the phone
 * refuses to use without the unlock.
 *
 * Needs API 28 for [BiometricPrompt] with a crypto object; older phones
 * register no key and cannot read remotely (the desktop says so plainly).
 */
class BiometricSigner(private val context: Context) {
    private val store: KeyStore by lazy { KeyStore.getInstance("AndroidKeyStore").apply { load(null) } }

    /** True when this phone can register and use a biometric key at all. */
    val supported: Boolean get() = Build.VERSION.SDK_INT >= 28 && canAuthenticate()

    private fun canAuthenticate(): Boolean {
        if (Build.VERSION.SDK_INT < 29) return true // API 28: no query API; the prompt itself tells
        val bm = context.getSystemService(BiometricManager::class.java) ?: return false
        return if (Build.VERSION.SDK_INT >= 30) bm.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL) == BiometricManager.BIOMETRIC_SUCCESS
        else @Suppress("DEPRECATION") (bm.canAuthenticate() == BiometricManager.BIOMETRIC_SUCCESS)
    }

    /** Makes the key on first use; returns its SEC1 public point as hex (65 bytes), or null when unsupported. */
    fun publicKeyHex(): String? {
        if (!supported) return null
        return runCatching {
            if (!store.containsAlias(ALIAS)) generate()
            val pub = store.getCertificate(ALIAS)?.publicKey ?: return null
            Assertion.sec1FromSpki(pub.encoded)
        }.getOrNull()
    }

    private fun generate() {
        val b = KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN)
            .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
            .setDigests(KeyProperties.DIGEST_SHA256)
            .setUserAuthenticationRequired(true)
        if (Build.VERSION.SDK_INT >= 30) b.setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG or KeyProperties.AUTH_DEVICE_CREDENTIAL)
        else @Suppress("DEPRECATION") b.setUserAuthenticationValidityDurationSeconds(-1)
        if (Build.VERSION.SDK_INT >= 28) b.setInvalidatedByBiometricEnrollment(false)
        KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore").apply { initialize(b.build()) }.generateKeyPair()
    }

    /** Drops the key; the desktop then has to be paired again to read remotely. */
    fun reset() { runCatching { store.deleteEntry(ALIAS) } }

    /**
     * Shows the system prompt and signs `cued-bio|device|challenge`. Returns
     * the `X-Cued-Assertion` value, or a failure with a reason a person can
     * read (cancelled, no key, unsupported).
     */
    suspend fun assertion(activity: Activity, device: String, challenge: String, title: String = "Unlock to read from your desktop"): Result<String> {
        if (Build.VERSION.SDK_INT < 28 || !supported) return Result.failure(IllegalStateException("This phone cannot sign biometric assertions (Android 9 or newer with a screen lock is needed)"))
        val key = runCatching { store.getKey(ALIAS, null) as? PrivateKey }.getOrNull() ?: return Result.failure(IllegalStateException("No biometric key yet: pair with the desktop on its Wi-Fi first"))
        val sig = Signature.getInstance("SHA256withECDSA").apply { initSign(key) }
        return suspendCoroutine { cont ->
            val builder = BiometricPrompt.Builder(activity).setTitle(title).setSubtitle("The desktop only serves reads from outside the LAN after this unlock.")
            if (Build.VERSION.SDK_INT >= 30) builder.setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL)
            else builder.setNegativeButton("Cancel", activity.mainExecutor) { _, _ -> }
            val prompt = builder.build()
            val callback = object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    val s = result.cryptoObject?.signature ?: sig
                    val out = runCatching { s.update(Assertion.message(device, challenge)); s.sign() }
                    cont.resume(out.map { Assertion.header(challenge, it) })
                }
                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) { cont.resume(Result.failure(IllegalStateException(errString.toString()))) }
            }
            prompt.authenticate(BiometricPrompt.CryptoObject(sig), CancellationSignal(), activity.mainExecutor, callback)
        }
    }

    companion object { private const val ALIAS = "cued-desktop-bio" }
}
