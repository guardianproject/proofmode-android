package org.witness.proofmode.notaries

import android.content.Context
import rust.nostr.sdk.Keys
import timber.log.Timber

/**
 * Single source of truth for the ProofMode Nostr notary identity (the secp256k1 keypair used
 * to sign notarization events). The secret key is persisted as a bech32 `nsec` in a private
 * [android.content.SharedPreferences] file.
 *
 * Used by both [NostrNotarizationProvider] (which signs with the current identity) and the
 * Nostr Identity settings screen (which lets the user view, generate, import or back up the
 * key). Keeping storage here ensures the provider always signs with whatever identity the user
 * has configured.
 */
object NostrIdentityManager {

    private const val PREFS_NAME = "proofmode_nostr"
    private const val PREF_KEY_NSEC = "nsec"

    /**
     * Load the persisted notary keys, generating and persisting a fresh identity on first use.
     * This is the path used by the provider when signing.
     */
    fun getOrCreateKeys(context: Context): Keys {
        val stored = storedNsec(context)
        if (stored != null) {
            try {
                return Keys.parse(stored)
            } catch (t: Throwable) {
                Timber.w(t, "Stored Nostr nsec was invalid; regenerating")
            }
        }
        val keys = Keys.generate()
        persist(context, keys)
        return keys
    }

    /** The current identity's npub (bech32 public key), or null if none has been created yet. */
    fun getNpub(context: Context): String? = loadKeys(context)?.publicKey()?.toBech32()

    /** The current identity's nsec (bech32 secret key), for backup, or null if none exists. */
    fun getNsec(context: Context): String? = storedNsec(context)

    fun hasIdentity(context: Context): Boolean = storedNsec(context) != null

    /** Generate a brand new identity, replacing any existing one. Returns the new npub. */
    fun generate(context: Context): String {
        val keys = Keys.generate()
        persist(context, keys)
        return keys.publicKey().toBech32()
    }

    /**
     * Import an identity from a user-supplied secret key (bech32 `nsec1…` or hex). Returns the
     * resulting npub. Throws if the input is not a valid Nostr secret key.
     */
    fun import(context: Context, secretKeyInput: String): String {
        val keys = Keys.parse(secretKeyInput.trim())
        persist(context, keys)
        return keys.publicKey().toBech32()
    }

    private fun loadKeys(context: Context): Keys? {
        val stored = storedNsec(context) ?: return null
        return try {
            Keys.parse(stored)
        } catch (t: Throwable) {
            Timber.w(t, "Stored Nostr nsec was invalid")
            null
        }
    }

    private fun storedNsec(context: Context): String? =
        context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(PREF_KEY_NSEC, null)

    private fun persist(context: Context, keys: Keys) {
        context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(PREF_KEY_NSEC, keys.secretKey().toBech32())
            .apply()
    }
}
