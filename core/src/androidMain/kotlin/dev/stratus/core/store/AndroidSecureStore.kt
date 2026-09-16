package dev.stratus.core.store

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Secrets under a key that never leaves the secure hardware.
 *
 * Worth being exact about what this is, because the shape invites a wrong
 * assumption: the Android Keystore stores *keys*, not arbitrary secrets. There
 * is no equivalent of the iOS Keychain's generic-password item, so every Android
 * implementation of this -- `androidx.security:security-crypto` included -- is a
 * Keystore key wrapping a blob in an ordinary file. What the file holds is
 * ciphertext nothing can read without a key the app itself cannot export, which
 * satisfies "never in ordinary preferences" in substance if not in letter.
 *
 * `androidx.security:security-crypto` would do the same thing for the price of a
 * dependency, and it is deprecated besides.
 *
 * No user authentication is required to use the key, deliberately: the app
 * uploads while the phone is locked on a charger overnight, and a key that
 * needed an unlock would make the backup stop exactly when it is supposed to run.
 */
class AndroidSecureStore(context: Context) : SecureStore {

    private val preferences = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    override suspend fun read(key: String): String? =
        try {
            preferences.getString(key, null)?.let(::decrypt)
        } catch (_: GeneralSecurityException) {
            // Restoring app data onto a new phone brings this file but not the
            // Keystore key, so none of it can be read. That is "you are signed
            // out", which is a state, rather than a crash on first launch.
            preferences.edit().clear().apply()
            null
        }

    override suspend fun write(key: String, value: String) {
        preferences.edit().putString(key, encrypt(value)).apply()
    }

    override suspend fun delete(key: String) {
        preferences.edit().remove(key).apply()
    }

    private fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val body = cipher.doFinal(plain.toByteArray())
        return Base64.getEncoder().encodeToString(cipher.iv + body)
    }

    private fun decrypt(stored: String): String {
        val raw = Base64.getDecoder().decode(stored)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, raw, 0, IV_BYTES))
        return String(cipher.doFinal(raw, IV_BYTES, raw.size - IV_BYTES))
    }

    private fun key(): SecretKey {
        val keystore = KeyStore.getInstance(PROVIDER).apply { load(null) }
        (keystore.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER)
        generator.init(
            KeyGenParameterSpec.Builder(
                ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setUserAuthenticationRequired(false)
                .build(),
        )
        return generator.generateKey()
    }

    private companion object {
        const val FILE = "dev.stratus.secure"
        const val PROVIDER = "AndroidKeyStore"
        const val ALIAS = "dev.stratus.credentials"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
        const val TAG_BITS = 128
    }
}
