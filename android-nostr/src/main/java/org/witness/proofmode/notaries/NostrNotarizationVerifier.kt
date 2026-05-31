package org.witness.proofmode.notaries

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import rust.nostr.sdk.Client
import rust.nostr.sdk.Event
import rust.nostr.sdk.EventId
import rust.nostr.sdk.Filter
import rust.nostr.sdk.RelayUrl
import timber.log.Timber
import java.time.Duration

/**
 * Verifies ProofMode Nostr notarizations produced by [NostrNotarizationProvider].
 *
 * The core check is fully offline and cryptographic: it re-derives the event id and verifies
 * the BIP-340 Schnorr signature of the stored event, then pulls the notarized media hash back
 * out of the event's indexable `x` tag. A valid result proves that the holder of the notary
 * key signed that exact media hash at the event's `created_at` time.
 *
 * Optionally, [existsOnRelays] can confirm that the event is still publicly retrievable.
 */
object NostrNotarizationVerifier {

    /**
     * @property valid    true only if the event id and Schnorr signature both verify.
     * @property mediaHash the SHA-256 hash that was notarized (from the event's `x` tag), if present.
     */
    data class Result(
        val valid: Boolean,
        val eventId: String? = null,
        val npub: String? = null,
        val pubkey: String? = null,
        val createdAt: Long? = null,
        val mediaHash: String? = null,
        val content: String? = null,
        val error: String? = null
    )

    /**
     * Verify a `.nostr` notarization record (the JSON written by the provider) offline.
     * Accepts either the full record object (`{ ..., "event": { ... } }`) or a bare Nostr
     * event object.
     */
    fun verify(recordJson: String): Result {
        return try {
            val root = JSONObject(recordJson)
            val eventObj = if (root.has("event")) root.getJSONObject("event") else root
            val event = Event.fromJson(eventObj.toString())

            // Verifies both the event id and the Schnorr signature.
            val valid = event.verify()

            Result(
                valid = valid,
                eventId = eventObj.optString("id").ifEmpty { null },
                npub = if (root.has("npub")) root.optString("npub").ifEmpty { null } else null,
                pubkey = eventObj.optString("pubkey").ifEmpty { null },
                createdAt = if (eventObj.has("created_at")) eventObj.getLong("created_at") else null,
                mediaHash = extractMediaHash(eventObj),
                content = eventObj.optString("content").ifEmpty { null },
                error = if (valid) null else "Event id/signature verification failed"
            )
        } catch (t: Throwable) {
            Result(valid = false, error = t.message ?: t.javaClass.simpleName)
        }
    }

    /**
     * Convenience: verify the media hash a `.nostr` record claims to notarize actually matches
     * the expected hash AND the event signature is valid.
     */
    fun verifyMatches(recordJson: String, expectedMediaHash: String): Boolean {
        val result = verify(recordJson)
        return result.valid && result.mediaHash.equals(expectedMediaHash, ignoreCase = true)
    }

    /**
     * Optionally confirm the notarization event is still retrievable from at least one relay.
     * Network call; returns false on timeout or if no relay returns the event.
     */
    fun existsOnRelays(
        recordJson: String,
        relays: List<String>,
        timeoutMs: Long = 15_000L
    ): Boolean {
        val eventIdHex = try {
            val root = JSONObject(recordJson)
            val eventObj = if (root.has("event")) root.getJSONObject("event") else root
            eventObj.optString("id").ifEmpty { return false }
        } catch (t: Throwable) {
            return false
        }

        return runBlocking {
            withTimeoutOrNull(timeoutMs) {
                val client = Client()
                try {
                    for (relay in relays) {
                        client.addRelay(RelayUrl.parse(relay))
                    }
                    client.connect()
                    val filter = Filter().id(EventId.parse(eventIdHex)).limit(1u)
                    val events = client.fetchEvents(filter, Duration.ofMillis(timeoutMs))
                    events.toVec().isNotEmpty()
                } finally {
                    try {
                        client.shutdown()
                    } catch (ignored: Throwable) {
                    }
                }
            } ?: false
        }
    }

    private fun extractMediaHash(eventObj: JSONObject): String? {
        val tags = eventObj.optJSONArray("tags") ?: return null
        for (i in 0 until tags.length()) {
            val tag = tags.optJSONArray(i) ?: continue
            if (tag.length() >= 2 && tag.optString(0) == "x") {
                return tag.optString(1).ifEmpty { null }
            }
        }
        return null
    }
}
