package org.witness.proofmode.c2pa

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by
    preferencesDataStore(name = "c2pa_settings")

class PreferencesManager(private val context: Context) {

    companion object {
        val SIGNING_MODE_KEY = stringPreferencesKey("signing_mode")
        val REMOTE_URL_KEY = stringPreferencesKey("remote_url")
        val REMOTE_TOKEN_KEY = stringPreferencesKey("remote_token")
        val CUSTOM_CERT_KEY = stringPreferencesKey("custom_cert")
        val CUSTOM_KEY_KEY = stringPreferencesKey("custom_key")
        val HARDWARE_KEY_ALIAS = stringPreferencesKey("hardware_key_alias")
        val SOFTWARE_CERT_KEY = stringPreferencesKey("software_cert")
        val SOFTWARE_KEY_KEY = stringPreferencesKey("software_key")
        val CUSTOM_KEY_HASH = stringPreferencesKey("custom_key_hash")
    }

    val signingMode: Flow<SigningMode> =
        context.dataStore.data.map { preferences ->
            val mode = preferences[SIGNING_MODE_KEY] ?: SigningMode.KEYSTORE.name
            SigningMode.fromString(mode)
        }

    suspend fun setSigningMode(mode: SigningMode) {
        context.dataStore.edit { preferences -> preferences[SIGNING_MODE_KEY] = mode.name }
    }

    val remoteUrl: Flow<String?> =
        context.dataStore.data.map { preferences -> preferences[REMOTE_URL_KEY] }

    suspend fun setRemoteUrl(url: String) {
        context.dataStore.edit { preferences -> preferences[REMOTE_URL_KEY] = url }
    }

    val remoteToken: Flow<String?> =
        context.dataStore.data.map { preferences -> preferences[REMOTE_TOKEN_KEY] }

    suspend fun setRemoteToken(token: String) {
        context.dataStore.edit { preferences -> preferences[REMOTE_TOKEN_KEY] = token }
    }

    val customCertificate: Flow<String?> =
        context.dataStore.data.map { preferences -> preferences[CUSTOM_CERT_KEY] }

    suspend fun setCustomCertificate(cert: String) {
        context.dataStore.edit { preferences -> preferences[CUSTOM_CERT_KEY] = cert }
    }

    val customPrivateKey: Flow<String?> =
        context.dataStore.data.map { preferences -> preferences[CUSTOM_KEY_KEY] }

    suspend fun setCustomPrivateKey(key: String) {
        context.dataStore.edit { preferences -> preferences[CUSTOM_KEY_KEY] = key }
    }

    suspend fun clearCustomCertificates() {
        context.dataStore.edit { preferences ->
            preferences.remove(CUSTOM_CERT_KEY)
            preferences.remove(CUSTOM_KEY_KEY)
        }
    }

    val hardwareKeyAlias: Flow<String?> =
        context.dataStore.data.map { preferences -> preferences[HARDWARE_KEY_ALIAS] }

    suspend fun setHardwareKeyAlias(alias: String) {
        context.dataStore.edit { preferences -> preferences[HARDWARE_KEY_ALIAS] = alias }
    }

    val softwareCertificate: Flow<String?> =
        context.dataStore.data.map { preferences -> preferences[SOFTWARE_CERT_KEY] }

    suspend fun setSoftwareCertificate(cert: String) {
        context.dataStore.edit { preferences -> preferences[SOFTWARE_CERT_KEY] = cert }
    }

    val softwarePrivateKey: Flow<String?> =
        context.dataStore.data.map { preferences -> preferences[SOFTWARE_KEY_KEY] }

    suspend fun setSoftwarePrivateKey(key: String) {
        context.dataStore.edit { preferences -> preferences[SOFTWARE_KEY_KEY] = key }
    }

    val customKeyHash: Flow<String?> =
        context.dataStore.data.map { preferences -> preferences[CUSTOM_KEY_HASH] }

    suspend fun setCustomKeyHash(hash: String) {
        context.dataStore.edit { preferences -> preferences[CUSTOM_KEY_HASH] = hash }
    }
}
