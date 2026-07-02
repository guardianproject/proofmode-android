package org.witness.proofmode.plugins.wallet.infra.factory

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

class WalletSessionStore(context: Context) {
    private val prefs = EncryptedSharedPreferences.create(
        context.applicationContext,
        PREFS_NAME,
        MasterKey.Builder(context.applicationContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    @Volatile
    private var onSponsorshipPrefsChanged: (() -> Unit)? = null

    fun saveChainId(chainId: String) {
        prefs.edit().putString(KEY_CHAIN_ID, chainId).apply()
    }

    fun loadChainId(): String? = prefs.getString(KEY_CHAIN_ID, null)

    fun isSponsorTransactionsEnabled(): Boolean =
        prefs.getBoolean(KEY_SPONSOR_TRANSACTIONS_ENABLED, true)

    fun saveSponsorTransactionsEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_SPONSOR_TRANSACTIONS_ENABLED, enabled).apply()
        notifySponsorshipPrefsChanged()
    }

    fun loadZeroDevProjectIdOverride(): String? =
        prefs.getString(KEY_ZERODEV_PROJECT_ID_OVERRIDE, null)?.takeIf { it.isNotBlank() }

    fun saveZeroDevProjectIdOverride(projectId: String?) {
        prefs.edit().apply {
            if (projectId.isNullOrBlank()) {
                remove(KEY_ZERODEV_PROJECT_ID_OVERRIDE)
            } else {
                putString(KEY_ZERODEV_PROJECT_ID_OVERRIDE, projectId.trim())
            }
        }.apply()
        notifySponsorshipPrefsChanged()
    }

    fun setOnSponsorshipPrefsChangedListener(listener: (() -> Unit)?) {
        onSponsorshipPrefsChanged = listener
    }

    private fun notifySponsorshipPrefsChanged() {
        onSponsorshipPrefsChanged?.invoke()
    }

    fun clear() {
        prefs.edit()
            .remove(KEY_CHAIN_ID)
            .remove(KEY_SPONSOR_TRANSACTIONS_ENABLED)
            .remove(KEY_ZERODEV_PROJECT_ID_OVERRIDE)
            .apply()
    }

    companion object {
        private const val PREFS_NAME = "wallet_session_store"
        private const val KEY_CHAIN_ID = "last_chain_id"
        private const val KEY_SPONSOR_TRANSACTIONS_ENABLED = "sponsor_transactions_enabled"
        private const val KEY_ZERODEV_PROJECT_ID_OVERRIDE = "zerodev_project_id_override"
    }
}
