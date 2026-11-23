package dev.gazedot

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.Base64

/** A per-install private key stays in Android Keystore, never in the APK or backup. */
internal object InstallationProof {
    private const val ALIAS = "notouch.prediction.installation.v1"
    @Synchronized fun headers(path: String, body: ByteArray): Map<String, String> {
        require(path == "/v2/predictions")
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (!store.containsAlias(ALIAS)) {
            KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore").apply {
                initialize(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN)
                    .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                    .setDigests(KeyProperties.DIGEST_SHA256).build())
            }.generateKeyPair()
        }
        val entry = store.getEntry(ALIAS, null) as KeyStore.PrivateKeyEntry
        val time = System.currentTimeMillis().toString()
        val nonce = ByteArray(16).also { SecureRandom().nextBytes(it) }.hex()
        val signature = Signature.getInstance("SHA256withECDSA").run {
            initSign(entry.privateKey)
            update(canonical(time, nonce, body).toByteArray(Charsets.UTF_8))
            sign()
        }
        val encoder = Base64.getUrlEncoder().withoutPadding()
        return mapOf("X-NoTouch-Key" to encoder.encodeToString(entry.certificate.publicKey.encoded),
            "X-NoTouch-Time" to time, "X-NoTouch-Nonce" to nonce,
            "X-NoTouch-Signature" to encoder.encodeToString(signature))
    }
    internal fun canonical(time: String, nonce: String, body: ByteArray) =
        "POST\n/v2/predictions\n$time\n$nonce\n${MessageDigest.getInstance("SHA-256").digest(body).hex()}"
    private fun ByteArray.hex() = joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
