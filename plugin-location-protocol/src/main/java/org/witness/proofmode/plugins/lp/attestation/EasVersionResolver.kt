package org.witness.proofmode.plugins.lp.attestation

import org.witness.proofmode.plugins.lp.config.ChainConfig
import timber.log.Timber
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Picks the EAS version to sign into the domain: the live contract's answer, or the
 * table's when no RPC answers.
 *
 * A live answer is remembered per chain in [liveVersions], which defaults to a map shared by
 * the whole process: the plugin builds a fresh manager (and resolver) per attestation, so an
 * instance-level map would never be hit.
 */
internal class EasVersionResolver(
    rpcClient: EthJsonRpcClient,
    private val liveVersions: ConcurrentHashMap<String, String> = processLiveVersions,
) {

    // Signing waits on this read, so each call is cut well short of the client's default.
    private val rpcClient = rpcClient.withCallTimeout(CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)

    /**
     * [tableParams] yields the table's `easAddress to easVersion` for [chain]. It runs only on a
     * cache miss, so a hit costs neither a bridge round trip nor an RPC.
     */
    suspend fun resolve(chain: ChainConfig, tableParams: suspend () -> Pair<String, String>): String {
        liveVersions[chain.caip2Id]?.let { return it }
        val (easAddress, tableVersion) = tableParams()
        val live = rpcClient.readEasVersion(chain.rpcUrls, easAddress)
        if (live == null) {
            // A stale table version fails the EAS SDK's strict domain check on OP-stack chains,
            // so a fallback is worth a warning even though signing proceeds. It is not cached:
            // the next attestation should try the RPC again.
            Timber.tag(TAG).w(
                "EAS version read failed on every RPC for chainId=%s; signing table version %s",
                chain.caip2Id,
                tableVersion,
            )
            return tableVersion
        }
        // Racing misses may both read; the write winner's value is the one every caller returns,
        // and only the winner logs, so the difference appears once per chain.
        liveVersions.putIfAbsent(chain.caip2Id, live)?.let { return it }
        if (live != tableVersion) {
            Timber.tag(TAG).i(
                "Live EAS version differs from table for chainId=%s: live=%s table=%s; signing live",
                chain.caip2Id,
                live,
                tableVersion,
            )
        }
        return live
    }

    companion object {
        private const val TAG = "EasVersionResolver"
        private const val CALL_TIMEOUT_SECONDS = 5L

        internal val processLiveVersions = ConcurrentHashMap<String, String>()
    }
}
