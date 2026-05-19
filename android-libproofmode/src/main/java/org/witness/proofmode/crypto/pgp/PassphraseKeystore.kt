package org.witness.proofmode.crypto.pgp

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import timber.log.Timber
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Wraps the PGP secret-keyring passphrase under a non-extractable
 * AndroidKeyStore AES-256-GCM key. A forensic dump of the app data dir
 * yields only the wrapped blob; without the device-bound KEK the passphrase
 * is unrecoverable, so the secret keyring (`files/pkr.asc`) cannot be
 * decrypted off-device.
 *
 * The passphrase itself is 256 bits of SecureRandom output, URL-safe base64
 * encoded so BouncyCastle's char[] handling stays ASCII-clean.
 */
object PassphraseKeystore {
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val KEK_ALIAS = "pm_pgp_passphrase_kek"
    private const val PREFS_FILE = "pm_pgp_secret"
    private const val PREF_WRAPPED = "wrapped_passphrase"
    private const val GCM_TAG_BITS = 128
    private const val GCM_IV_BYTES = 12
    private const val PASSPHRASE_ENTROPY_BYTES = 32

    @Synchronized
    @JvmStatic
    fun getOrCreatePassphrase(context: Context): String {
        loadExisting(context)?.let { return it }
        val fresh = generatePassphrase()
        storePassphrase(context, fresh)
        return fresh
    }

    @JvmStatic
    fun hasPassphrase(context: Context): Boolean =
        prefs(context).contains(PREF_WRAPPED)

    /**
     * Wrap [passphrase] with the keystore KEK and persist it, replacing any
     * prior value. Used by the legacy-keyring migration path so the existing
     * keyring can be re-encrypted under the new strong passphrase.
     */
    @Synchronized
    @JvmStatic
    fun storePassphrase(context: Context, passphrase: String) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKek())
        val iv = cipher.iv
        val ct = cipher.doFinal(passphrase.toByteArray(Charsets.UTF_8))
        val blob = ByteArray(iv.size + ct.size).apply {
            System.arraycopy(iv, 0, this, 0, iv.size)
            System.arraycopy(ct, 0, this, iv.size, ct.size)
        }
        prefs(context).edit()
            .putString(PREF_WRAPPED, Base64.encodeToString(blob, Base64.NO_WRAP))
            .commit()
    }

    private fun loadExisting(context: Context): String? {
        val b64 = prefs(context).getString(PREF_WRAPPED, null) ?: return null
        return try {
            val blob = Base64.decode(b64, Base64.NO_WRAP)
            if (blob.size <= GCM_IV_BYTES) return null
            val iv = blob.copyOfRange(0, GCM_IV_BYTES)
            val ct = blob.copyOfRange(GCM_IV_BYTES, blob.size)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKek(), GCMParameterSpec(GCM_TAG_BITS, iv))
            String(cipher.doFinal(ct), Charsets.UTF_8)
        } catch (e: Exception) {
            Timber.w(e, "Failed to decrypt wrapped PGP passphrase")
            null
        }
    }

    /**
     * Generate a fresh 256-bit passphrase without persisting it. Callers
     * performing a multi-step migration use this so the new passphrase only
     * lands in the keystore wrapper *after* the on-disk keyring has been
     * successfully rewritten under it (atomic-ish reconciliation).
     */
    @JvmStatic
    fun generatePassphrase(): String {
        val raw = ByteArray(PASSPHRASE_ENTROPY_BYTES)
        SecureRandom().nextBytes(raw)
        return Base64.encodeToString(
            raw,
            Base64.NO_WRAP or Base64.URL_SAFE or Base64.NO_PADDING,
        )
    }

    private fun getOrCreateKek(): SecretKey {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        ks.getKey(KEK_ALIAS, null)?.let { return it as SecretKey }

        val spec = KeyGenParameterSpec.Builder(
            KEK_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build()
        val kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        kg.init(spec)
        return kg.generateKey()
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)
}
