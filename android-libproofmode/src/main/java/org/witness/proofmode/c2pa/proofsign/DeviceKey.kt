package org.witness.proofmode.c2pa.proofsign

import android.content.Context
import android.content.SharedPreferences
import java.security.KeyStore
import java.security.PrivateKey
import java.security.Signature
import java.util.Base64
import java.util.UUID

/**
 * This device's ProofSign identity: its ID, whether it has completed Key Attestation
 * registration with a given server, and the attested keystore key that signs its requests.
 * Shared by [ProofSignClient] (sign requests) and the timestamp relay, so neither builds the
 * other just to reach the key.
 */
internal class DeviceKey(context: Context) {

    companion object {
        const val KEYSTORE_ALIAS = "proofsign_device_key"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val PREFS = "proofsign_prefs"
        private const val PREF_DEVICE_ID = "device_id"
        private const val PREF_DEVICE_REGISTERED = "device_registered"
        private const val PREF_LAST_SERVER_URL = "last_server_url"
    }

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun deviceId(): String =
        prefs.getString(PREF_DEVICE_ID, null)
            ?: UUID.randomUUID().toString().also { prefs.edit().putString(PREF_DEVICE_ID, it).apply() }

    /** Registered with whichever server [invalidateIfServerChanged] last recorded. */
    fun isRegistered(): Boolean = prefs.getBoolean(PREF_DEVICE_REGISTERED, false)

    fun isRegisteredWith(serverUrl: String): Boolean =
        registeredWith(isRegistered(), prefs.getString(PREF_LAST_SERVER_URL, null), serverUrl)

    fun markRegistered() {
        prefs.edit().putBoolean(PREF_DEVICE_REGISTERED, true).apply()
    }

    /**
     * A registration belongs to one server: when [serverUrl] differs from the last one,
     * drop it so the next sign re-registers against the new server.
     */
    fun invalidateIfServerChanged(serverUrl: String): Boolean {
        if (prefs.getString(PREF_LAST_SERVER_URL, null) == serverUrl) return false
        prefs.edit()
            .putString(PREF_LAST_SERVER_URL, serverUrl)
            .putBoolean(PREF_DEVICE_REGISTERED, false)
            .apply()
        return true
    }

    /** SHA256withECDSA with the attested key, standard Base64. */
    fun sign(data: ByteArray): String {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        val privateKey = keyStore.getKey(KEYSTORE_ALIAS, null) as PrivateKey
        return Signature.getInstance("SHA256withECDSA").run {
            initSign(privateKey)
            update(data)
            Base64.getEncoder().encodeToString(sign())
        }
    }

    fun requestSigner(): DeviceRequestSigner =
        deviceRequestSigner(deviceId(), System::currentTimeMillis, ::sign)
}

/** A registration counts only for the server it was made with. */
internal fun registeredWith(registered: Boolean, lastServerUrl: String?, serverUrl: String): Boolean =
    registered && lastServerUrl == serverUrl
