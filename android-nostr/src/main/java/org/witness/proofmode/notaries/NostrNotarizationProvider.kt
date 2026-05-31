package org.witness.proofmode.notaries

import android.content.Context
import android.preference.PreferenceManager
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import org.witness.proofmode.ProofMode
import org.witness.proofmode.notarization.NotarizationListener
import org.witness.proofmode.notarization.NotarizationProvider
import rust.nostr.sdk.Client
import rust.nostr.sdk.Event
import rust.nostr.sdk.EventBuilder
import rust.nostr.sdk.Keys
import rust.nostr.sdk.NostrSigner
import rust.nostr.sdk.RelayUrl
import rust.nostr.sdk.Tag
import timber.log.Timber
import java.io.IOException
import java.io.InputStream

/**
 * A [NotarizationProvider] that notarizes a ProofMode media hash by publishing it as a
 * signed note to the Nostr network.
 *
 * Nostr is permission-less: this provider generates its own secp256k1 key (nsec) on first
 * use, persists it privately on-device, and re-uses it as a stable ProofMode notary identity
 * for every subsequent note. Each notarization is a Schnorr-signed kind-1 event that embeds
 * the media's SHA-256 hash (both as readable content and as an indexable `x` tag) and is
 * broadcast to a set of public relays.
 *
 * The full signed event (which is self-verifying and can be re-published or looked up on any
 * relay) is handed back to the [NotarizationListener] for storage in the ProofMode hash
 * folder as a `.nostr` file, mirroring the way [OpenTimestampsNotarizationProvider] stores
 * its `.ots` proof.
 *
 * Created for ProofMode by adding Nostr as a notarization target.
 */
class NostrNotarizationProvider(context: Context) : NotarizationProvider {

    private val appContext: Context = context.applicationContext

    override fun notarize(
        mediaHash: String,
        mimeType: String?,
        `is`: InputStream?,
        listener: NotarizationListener
    ) {
        // We notarize the hash, not the bytes, so the stream is not needed here.
        try {
            `is`?.close()
        } catch (ignored: IOException) {
        }

        if (!isEnabled()) {
            Timber.d("Nostr notarization disabled by user setting; skipping %s", mediaHash)
            return
        }

        try {
            val record = runBlocking { publish(mediaHash, mimeType) }
            listener.notarizationSuccessful(mediaHash, record)
        } catch (t: Throwable) {
            // Reached only if signing/key generation itself fails. Relay/network failures are
            // captured inside the record and still saved, since the signed event is itself a
            // complete, self-verifying notarization.
            Timber.e(t, "Nostr notarization failed for %s", mediaHash)
            listener.notarizationFailed(-1, t.message ?: t.javaClass.simpleName)
        }
    }

    @Throws(IOException::class)
    override fun getProof(hash: String): String? {
        return null
    }

    override fun getNotarizationFileExtension(): String {
        return ProofMode.NOSTR_FILE_TAG
    }

    /**
     * Sign the notarization event and best-effort broadcast it to the relays, returning the
     * JSON record to store. The signed event is always saved (with a `published` flag) even if
     * no relay could be reached, because the event is itself a complete, self-verifying
     * notarization. Only throws if signing/key generation fails.
     */
    private suspend fun publish(mediaHash: String, mimeType: String?): String {
        val keys = getOrCreateKeys()
        val signer = NostrSigner.keys(keys)

        val content = buildString {
            append("Proofmode notarization\n")
            append("SHA-256: ").append(mediaHash)
            if (!mimeType.isNullOrEmpty()) {
                append("\nType: ").append(mimeType)
            }
        }

        val tags = listOf(
            Tag.hashtag("proofmode"),
            // Indexable hash tag so the notarization can be looked up by media hash.
            Tag.parse(listOf("x", mediaHash)),
            Tag.alt("Proofmode media hash notarization")
        )

        // Sign locally first: the signed event is a complete, self-verifying notarization.
        val event = EventBuilder.textNote(content).tags(tags).sign(signer)
        val eventJson = event.asJson()

        // Broadcasting to relays is best-effort. A timeout or relay failure must NOT lose the
        // notarization, so capture the outcome and still return a record to be saved.
        val broadcast = try {
            withTimeoutOrNull(PUBLISH_TIMEOUT_MS) { broadcast(event, signer) }
                ?: BroadcastResult(0, DEFAULT_RELAYS.size, "timeout after ${PUBLISH_TIMEOUT_MS}ms")
        } catch (t: Throwable) {
            Timber.w(t, "Nostr relay broadcast failed for %s; saving unpublished event", mediaHash)
            BroadcastResult(0, DEFAULT_RELAYS.size, t.message ?: t.javaClass.simpleName)
        }

        if (broadcast.accepted > 0) {
            Timber.d(
                "Published Nostr notarization %s to %d relay(s)",
                event.id().toHex(),
                broadcast.accepted
            )
        }

        val record = JSONObject().apply {
            put("provider", "nostr")
            put("published", broadcast.accepted > 0)
            put("eventId", event.id().toHex())
            put("note", event.id().toBech32())
            put("npub", keys.publicKey().toBech32())
            put("pubkey", keys.publicKey().toHex())
            put("relays", JSONArray(DEFAULT_RELAYS))
            put("relaysAccepted", broadcast.accepted)
            put("relaysFailed", broadcast.failed)
            broadcast.error?.let { put("publishError", it) }
            // The full signed event: self-verifying and re-publishable on any relay.
            put("event", JSONObject(eventJson))
        }
        return record.toString(2)
    }

    /** Connect to the relays and publish the signed event, reporting how many accepted it. */
    private suspend fun broadcast(event: Event, signer: NostrSigner): BroadcastResult {
        val client = Client(signer = signer)
        return try {
            for (relay in DEFAULT_RELAYS) {
                client.addRelay(RelayUrl.parse(relay))
            }
            client.connect()
            val output = client.sendEvent(event)
            BroadcastResult(output.success.size, output.failed.size)
        } finally {
            try {
                client.shutdown()
            } catch (ignored: Throwable) {
            }
        }
    }

    private data class BroadcastResult(val accepted: Int, val failed: Int, val error: String? = null)

    /** Whether the user has the Nostr notarization provider enabled (in addition to the global toggle). */
    private fun isEnabled(): Boolean {
        val prefs = PreferenceManager.getDefaultSharedPreferences(appContext)
        return prefs.getBoolean(
            ProofMode.PREF_OPTION_NOTARY_NOSTR,
            ProofMode.PREF_OPTION_NOTARY_NOSTR_DEFAULT
        )
    }

    /**
     * Load the persisted notary keys (the user-configured identity), generating one on first
     * use. Storage and identity management live in [NostrIdentityManager] so the settings
     * screen and this provider share a single source of truth.
     */
    private fun getOrCreateKeys(): Keys = NostrIdentityManager.getOrCreateKeys(appContext)

    companion object {
        private const val PUBLISH_TIMEOUT_MS = 90_000L

        /** Public relays the notarization is broadcast to (configurable). */
        private val DEFAULT_RELAYS = listOf(
            "wss://relay.damus.io",
            "wss://nos.lol",
            "wss://relay.nostr.band"
        )
    }
}
