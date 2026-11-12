package org.witness.proofmode.plugins.wallet.infra.factory

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import org.json.JSONArray
import org.json.JSONObject

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

    /**
     * The last successful probe's sponsored chain ids for [projectId], or null when that project
     * has no cached answer. An empty list is a cached answer of "nothing sponsored".
     */
    fun loadSponsoredChains(projectId: String): List<String>? {
        val entry = prefs.getString(KEY_SPONSORED_CHAINS, null) ?: return null
        val json = JSONObject(entry)
        if (json.getString(ENTRY_PROJECT_ID) != projectId) return null
        val chains = json.getJSONArray(ENTRY_CHAINS)
        return List(chains.length()) { chains.getString(it) }
    }

    // One entry for the latest project only: an override switch replaces the old project's set
    // instead of accumulating one per id. Does not notify the sponsorship-prefs listener, which
    // would restart the refresh that produced this write.
    fun saveSponsoredChains(projectId: String, chainIds: List<String>) {
        val entry = JSONObject()
            .put(ENTRY_PROJECT_ID, projectId)
            .put(ENTRY_CHAINS, JSONArray(chainIds))
        prefs.edit().putString(KEY_SPONSORED_CHAINS, entry.toString()).apply()
    }

    fun setOnSponsorshipPrefsChangedListener(listener: (() -> Unit)?) {
        onSponsorshipPrefsChanged = listener
    }

    private fun notifySponsorshipPrefsChanged() {
        onSponsorshipPrefsChanged?.invoke()
    }

    companion object {
        private const val PREFS_NAME = "wallet_session_store"
        private const val KEY_CHAIN_ID = "last_chain_id"
        private const val KEY_SPONSOR_TRANSACTIONS_ENABLED = "sponsor_transactions_enabled"
        private const val KEY_ZERODEV_PROJECT_ID_OVERRIDE = "zerodev_project_id_override"
        private const val KEY_SPONSORED_CHAINS = "sponsored_chains"
        private const val ENTRY_PROJECT_ID = "projectId"
        private const val ENTRY_CHAINS = "chains"
    }
}
