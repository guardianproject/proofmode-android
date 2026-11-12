package org.witness.proofmode.plugins.lp.attestation

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.witness.proofmode.plugins.lp.attestation.FakeEthRpc.Companion.abiString
import org.witness.proofmode.plugins.lp.attestation.FakeEthRpc.Reply
import org.witness.proofmode.plugins.lp.config.ChainConfig
import org.witness.proofmode.plugins.lp.config.SUPPORTED_CHAINS
import java.util.concurrent.ConcurrentHashMap

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class EasVersionResolverTest {

    private val inkSepolia = SUPPORTED_CHAINS.first { it.caip2Id == "eip155:763373" }
    private val easAddress = "0x4200000000000000000000000000000000000021"
    private val tableVersion = "1.4.1-beta.1"
    private val ethereum = SUPPORTED_CHAINS.first { it.caip2Id == "eip155:1" }
    private val cache = ConcurrentHashMap<String, String>()
    private fun resolverOver(rpc: FakeEthRpc) = EasVersionResolver(EthJsonRpcClient(rpc.okHttpClient), cache)

    private suspend fun EasVersionResolver.resolve(chain: ChainConfig) =
        resolve(chain) { easAddress to tableVersion }

    @Test
    fun resolve_secondCallSameChain_makesNoRpcRequests() = runTest {
        val rpc = FakeEthRpc { Reply.Result(abiString("1.4.1-beta.3")) }
        val resolver = resolverOver(rpc)
        var tableQueries = 0

        val first = resolver.resolve(inkSepolia) { tableQueries++; easAddress to tableVersion }
        val requestsAfterFirst = rpc.calls.size
        val second = resolver.resolve(inkSepolia) { tableQueries++; easAddress to tableVersion }

        assertEquals("1.4.1-beta.3", first)
        assertEquals(easAddress, rpc.calls.first().to)
        assertEquals(first, second)
        assertTrue(requestsAfterFirst > 0)
        assertEquals(requestsAfterFirst, rpc.calls.size)
        assertEquals(1, tableQueries)
    }

    @Test
    fun resolve_twoChains_cacheIndependently() = runTest {
        val rpc = FakeEthRpc { call ->
            Reply.Result(abiString(if (call.url in inkSepolia.rpcUrls) "1.4.1-beta.3" else "0.26"))
        }
        val resolver = resolverOver(rpc)

        assertEquals("1.4.1-beta.3", resolver.resolve(inkSepolia))
        assertEquals("0.26", resolver.resolve(ethereum))
        val requestsAfterBoth = rpc.calls.size
        assertEquals("1.4.1-beta.3", resolver.resolve(inkSepolia))
        assertEquals("0.26", resolver.resolve(ethereum))

        assertEquals(requestsAfterBoth, rpc.calls.size)
    }

    @Test
    fun resolve_allRpcsFail_fallsBackAndIsNotCached_nextCallRetriesThenCaches() = runTest {
        var healthy = false
        val rpc = FakeEthRpc { if (healthy) Reply.Result(abiString("1.4.1-beta.3")) else Reply.Http(503) }
        val resolver = resolverOver(rpc)

        assertEquals(tableVersion, resolver.resolve(inkSepolia))
        val requestsAfterFailure = rpc.calls.size
        assertTrue(requestsAfterFailure > 0)

        healthy = true
        assertEquals("1.4.1-beta.3", resolver.resolve(inkSepolia))
        val requestsAfterRetry = rpc.calls.size
        assertTrue(requestsAfterRetry > requestsAfterFailure)
    }
}
