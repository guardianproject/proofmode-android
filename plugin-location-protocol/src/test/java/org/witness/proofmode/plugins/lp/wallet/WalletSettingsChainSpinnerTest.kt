package org.witness.proofmode.plugins.lp.wallet

import android.content.Context
import android.content.Intent
import android.os.Looper
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.test.core.app.ApplicationProvider
import com.google.android.material.switchmaterial.SwitchMaterial
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.timeout
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog
import org.witness.proofmode.plugins.lp.R
import org.witness.proofmode.plugins.lp.TestWalletStackReset
import org.witness.proofmode.plugins.lp.config.SUPPORTED_CHAINS
import org.witness.proofmode.plugins.lp.deeplink.WalletDeepLinkContract
import org.witness.proofmode.plugins.wallet.infra.api.WalletConnector
import org.witness.proofmode.plugins.wallet.infra.model.WalletDisconnected
import org.witness.proofmode.plugins.wallet.infra.model.WalletState
import org.witness.proofmode.plugins.wallet.infra.config.WalletChainPolicy
import org.witness.proofmode.plugins.wallet.infra.config.ZeroDevConfigResolver
import org.witness.proofmode.plugins.wallet.infra.model.WalletSdkConfig

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class WalletSettingsChainSpinnerTest {

    private val context get() = ApplicationProvider.getApplicationContext<Context>()

    @Before
    fun setUp() {
        FakeAndroidKeyStoreProvider.setup()
        TestWalletStackReset.reset()
    }

    private fun launch(
        intent: Intent = Intent(context, WalletSettingsActivity::class.java),
    ): WalletSettingsActivity {
        return Robolectric.buildActivity(WalletSettingsActivity::class.java, intent)
            .create().start().visible().get()
    }

    private var heldProbe: HeldProbe? = null

    /**
     * The post-probe sync hops to Dispatchers.IO, which the main looper cannot drain. Idles the
     * looper until the latest probe job completes; fails if it does not within [timeoutMs], so a
     * hung sync is a test failure and not a silent pass.
     */
    private fun settle(timeoutMs: Long = 3000) {
        val looper = shadowOf(Looper.getMainLooper())
        val deadline = System.nanoTime() + timeoutMs * 1_000_000
        while (true) {
            looper.idle()
            val job = heldProbe?.jobs?.lastOrNull()
            if (job == null || job.isCompleted) return
            check(System.nanoTime() < deadline) { "probe job still running after ${timeoutMs}ms" }
            Thread.sleep(10)
        }
    }

    /** Records each probe call and holds it until the test completes its answer. */
    private class HeldProbe {
        val calls = mutableListOf<Pair<String, List<String>>>()
        val jobs = mutableListOf<Job>()
        val answers = mutableListOf<CompletableDeferred<List<String>?>>()

        suspend fun probe(projectId: String, chainIds: List<String>): List<String>? {
            calls += projectId to chainIds
            jobs += currentCoroutineContext()[Job]!!
            val answer = CompletableDeferred<List<String>?>()
            answers += answer
            return answer.await()
        }
    }

    private fun installHeldProbe(): HeldProbe =
        HeldProbe().also {
            heldProbe = it
            WalletSigningPlugin.sponsorshipProbe = it::probe
        }

    /** The sponsored ids in catalog order, as the probe returns them. */
    private fun answer(vararg sponsored: String): List<String> =
        SUPPORTED_CHAINS.map { it.caip2Id }.filter { it in sponsored }

    private fun CompletableDeferred<List<String>?>.deliver(
        value: List<String>?,
    ) {
        complete(value)
        shadowOf(Looper.getMainLooper()).idle()
    }

    /** Delivers the current probe's answer and waits for the job, including its post-probe sync. */
    private fun CompletableDeferred<List<String>?>.deliverAndSettle(
        value: List<String>?,
    ) {
        deliver(value)
        settle()
    }

    private fun registerWithEffectiveProjectId(sponsorshipOn: Boolean = true) {
        WalletSigningPlugin.configure(WalletSdkConfig.fromBuildConfig())
        WalletSigningPlugin.register(context)
        WalletSigningPlugin.sessionStore()?.saveSponsorTransactionsEnabled(sponsorshipOn)
        ensureEffectiveProjectId()
    }

    private fun WalletSettingsActivity.spinner() = findViewById<Spinner>(R.id.spinner_chain)
    private fun WalletSettingsActivity.checkingText() =
        findViewById<TextView>(R.id.tv_checking_sponsored_networks)
    private fun WalletSettingsActivity.emptyNote() =
        findViewById<TextView>(R.id.tv_empty_sponsored_networks)

    private fun WalletSettingsActivity.couldNotCheckNote() =
        findViewById<TextView>(R.id.tv_could_not_check_sponsored_networks)
    private fun WalletSettingsActivity.retryButton() =
        findViewById<Button>(R.id.btn_retry_sponsored_networks)

    private fun effectiveProjectId(): String =
        ZeroDevConfigResolver.effectiveProjectId(
            sessionOverride = WalletSigningPlugin.sessionStore()?.loadZeroDevProjectIdOverride(),
            buildProjectId = WalletSigningPlugin.buildDefaultZeroDevProjectId(),
        )

    private fun seedCache(vararg chainIds: String) {
        WalletSigningPlugin.sessionStore()!!.saveSponsoredChains(effectiveProjectId(), chainIds.toList())
    }

    private fun cachedChains(projectId: String = effectiveProjectId()): List<String>? =
        WalletSigningPlugin.sessionStore()!!.loadSponsoredChains(projectId)

    private fun Spinner.items(): List<Any?> = (0 until adapter.count).map { adapter.getItem(it) }

    private fun ensureEffectiveProjectId() {
        val store = WalletSigningPlugin.sessionStore()
        if (!ZeroDevConfigResolver.isConfiguredSecret(effectiveProjectId())) {
            store?.saveZeroDevProjectIdOverride("550e8400-e29b-41d4-a716-446655440000")
        }
    }

    @Test
    fun sponsorshipOn_showsCheckingUntilAnswer_thenSponsoredChainsInCatalogOrder() {
        registerWithEffectiveProjectId()
        val probe = installHeldProbe()
        val activity = launch()
        val spinner = activity.spinner()

        assertEquals(0, spinner.adapter.count)
        assertFalse(spinner.isEnabled)
        assertEquals(View.VISIBLE, activity.checkingText().visibility)
        assertEquals(
            activity.getString(R.string.wallet_checking_sponsored_networks),
            activity.checkingText().text.toString(),
        )
        assertEquals(View.GONE, activity.emptyNote().visibility)

        probe.answers.single().deliverAndSettle(answer("eip155:421614", WalletChainPolicy.DEFAULT_CHAIN_ID, "eip155:1"))

        assertEquals(3, spinner.adapter.count)
        assertEquals("Ethereum Mainnet", spinner.adapter.getItem(0))
        assertEquals("Sepolia Testnet", spinner.adapter.getItem(1))
        assertEquals("Arbitrum Sepolia", spinner.adapter.getItem(2))
        assertEquals(1, spinner.selectedItemPosition)
        assertTrue(spinner.isEnabled)
        assertEquals(View.GONE, activity.checkingText().visibility)
        assertEquals(View.GONE, activity.emptyNote().visibility)
    }

    @Test
    fun sponsorshipOn_noEffectiveProjectId_neverProbes_andGraysOutWithNote() {
        // A build id, even a placeholder, is what enables the project-id field and save button.
        configureWithBuildProjectId("replace-me-placeholder")
        WalletSigningPlugin.register(context)
        WalletSigningPlugin.sessionStore()?.saveSponsorTransactionsEnabled(true)
        WalletSigningPlugin.sessionStore()?.saveZeroDevProjectIdOverride(null)
        val probe = installHeldProbe()

        val activity = launch()

        assertTrue(probe.calls.isEmpty())
        assertEquals(0, activity.spinner().adapter.count)
        assertFalse(activity.spinner().isEnabled)
        assertEquals(View.VISIBLE, activity.emptyNote().visibility)
        assertEquals(
            activity.getString(R.string.wallet_empty_sponsored_networks),
            activity.emptyNote().text.toString(),
        )
        assertEquals(View.GONE, activity.checkingText().visibility)

        activity.findViewById<EditText>(R.id.et_zerodev_project_id)
            .setText("550e8400-e29b-41d4-a716-446655440000")
        activity.findViewById<Button>(R.id.btn_save_zerodev_project_id).performClick()
        probe.answers.single().deliverAndSettle(answer(WalletChainPolicy.DEFAULT_CHAIN_ID, "eip155:421614"))

        assertEquals(2, activity.spinner().adapter.count)
        assertTrue(activity.spinner().isEnabled)
        assertEquals(View.GONE, activity.emptyNote().visibility)
    }

    @Test
    fun successfulAnswer_rebindsAndOverwritesCache() {
        registerWithEffectiveProjectId()
        seedCache(WalletChainPolicy.DEFAULT_CHAIN_ID, "eip155:1")
        val probe = installHeldProbe()
        val activity = launch()

        assertEquals(listOf<Any?>("Ethereum Mainnet", "Sepolia Testnet"), activity.spinner().items())
        assertTrue(activity.spinner().isEnabled)
        assertEquals(View.GONE, activity.checkingText().visibility)
        assertEquals(View.GONE, activity.emptyNote().visibility)
        assertEquals(1, probe.calls.size)

        probe.answers.single().deliverAndSettle(answer("eip155:421614", "eip155:1"))

        assertEquals(listOf<Any?>("Ethereum Mainnet", "Arbitrum Sepolia"), activity.spinner().items())
        assertEquals(listOf("eip155:1", "eip155:421614"), cachedChains())
    }

    @Test
    fun classifiedAnswerWithNothingSponsored_writesEmptyListAndShowsEmptyNote() {
        registerWithEffectiveProjectId()
        seedCache(WalletChainPolicy.DEFAULT_CHAIN_ID)
        val probe = installHeldProbe()
        val activity = launch()

        probe.answers.single().deliverAndSettle(answer())

        assertEquals(emptyList<String>(), cachedChains())
        assertEquals(0, activity.spinner().adapter.count)
        assertFalse(activity.spinner().isEnabled)
        assertEquals(View.VISIBLE, activity.emptyNote().visibility)
        assertEquals(View.GONE, activity.couldNotCheckNote().visibility)
        assertEquals(View.GONE, activity.retryButton().visibility)
    }

    @Test
    fun allUnknownAnswer_withCache_keepsCacheAndWritesNothing() {
        registerWithEffectiveProjectId()
        seedCache(WalletChainPolicy.DEFAULT_CHAIN_ID, "eip155:1")
        val probe = installHeldProbe()
        val activity = launch()

        probe.answers.single().deliverAndSettle(null)

        assertEquals(listOf<Any?>("Ethereum Mainnet", "Sepolia Testnet"), activity.spinner().items())
        assertTrue(activity.spinner().isEnabled)
        assertEquals(View.GONE, activity.emptyNote().visibility)
        assertEquals(View.GONE, activity.couldNotCheckNote().visibility)
        assertEquals(View.GONE, activity.checkingText().visibility)
        assertEquals(listOf(WalletChainPolicy.DEFAULT_CHAIN_ID, "eip155:1"), cachedChains())
    }

    @Test
    fun retryClick_reprobes_thenShowsChainsOnSuccess() {
        registerWithEffectiveProjectId()
        val probe = installHeldProbe()
        val activity = launch()
        probe.answers[0].deliverAndSettle(null)
        assertEquals(0, activity.spinner().adapter.count)
        assertFalse(activity.spinner().isEnabled)
        assertEquals(View.VISIBLE, activity.couldNotCheckNote().visibility)
        assertEquals(
            activity.getString(R.string.wallet_could_not_check_sponsored_networks),
            activity.couldNotCheckNote().text.toString(),
        )
        assertEquals(View.VISIBLE, activity.retryButton().visibility)
        assertEquals(View.GONE, activity.emptyNote().visibility)
        assertEquals(View.GONE, activity.checkingText().visibility)
        assertNull(cachedChains())

        activity.retryButton().performClick()
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(2, probe.calls.size)
        assertEquals(View.VISIBLE, activity.checkingText().visibility)
        assertEquals(View.GONE, activity.couldNotCheckNote().visibility)
        assertEquals(View.GONE, activity.retryButton().visibility)

        probe.answers[1].deliverAndSettle(answer(WalletChainPolicy.DEFAULT_CHAIN_ID))

        assertEquals(listOf<Any?>("Sepolia Testnet"), activity.spinner().items())
        assertEquals(View.GONE, activity.checkingText().visibility)
        assertEquals(View.GONE, activity.retryButton().visibility)
    }

    @Test
    fun toggleOn_servesCacheAndRefreshes() {
        registerWithEffectiveProjectId(sponsorshipOn = false)
        seedCache(WalletChainPolicy.DEFAULT_CHAIN_ID)
        val probe = installHeldProbe()
        val activity = launch()
        assertTrue(probe.calls.isEmpty())
        assertEquals(SUPPORTED_CHAINS.size, activity.spinner().adapter.count)

        activity.findViewById<SwitchMaterial>(R.id.switch_sponsor_transactions).isChecked = true
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(listOf<Any?>("Sepolia Testnet"), activity.spinner().items())
        assertEquals(View.GONE, activity.checkingText().visibility)
        assertEquals(1, probe.calls.size)
    }

    @Test
    fun selectionMadeDuringRefresh_survivesTheAnswer() {
        registerWithEffectiveProjectId()
        WalletSigningPlugin.sessionStore()!!.saveChainId(WalletChainPolicy.DEFAULT_CHAIN_ID)
        seedCache("eip155:1", WalletChainPolicy.DEFAULT_CHAIN_ID, "eip155:421614")
        val probe = installHeldProbe()
        val activity = launch()
        assertEquals("Sepolia Testnet", activity.spinner().selectedItem)

        activity.spinner().setSelection(2)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals("Arbitrum Sepolia", activity.spinner().selectedItem)

        probe.answers.single().deliverAndSettle(answer("eip155:1", WalletChainPolicy.DEFAULT_CHAIN_ID, "eip155:421614"))

        assertEquals("Arbitrum Sepolia", activity.spinner().selectedItem)
    }

    @Test
    fun spinnerPick_pushesPickedChainThroughActiveConnectorOnce() {
        registerWithEffectiveProjectId()
        WalletSigningPlugin.sessionStore()!!.saveChainId(WalletChainPolicy.DEFAULT_CHAIN_ID)
        seedCache(WalletChainPolicy.DEFAULT_CHAIN_ID, "eip155:421614")
        val connector = probeConnector()
        installActiveConnector(connector)
        installHeldProbe()
        val activity = launch()
        assertEquals("Sepolia Testnet", activity.spinner().selectedItem)

        activity.spinner().setSelection(1)
        shadowOf(Looper.getMainLooper()).idle()

        verify(connector, timeout(3000).times(1)).setChain("eip155:421614")
        verify(connector, times(1)).setChain(any())
    }

    private fun probeConnector(): WalletConnector = mock {
        on { stateFlow } doReturn MutableStateFlow<WalletState>(WalletDisconnected)
    }

    @Test
    fun probeAnswerExcludingSessionChain_remapsPersistsAndSpinnerShowsRemappedChain() {
        registerWithEffectiveProjectId()
        WalletSigningPlugin.sessionStore()!!.saveChainId("eip155:1")
        val connector = probeConnector()
        installActiveConnector(connector)
        val probe = installHeldProbe()
        // The screen opens on the store chain, Ethereum, which the answer excludes. The answer-time
        // bind from the store lands on Sepolia, and the sync persists the remap.
        val activity = launch()
        assertEquals(1, probe.calls.size)

        probe.answers.single().deliverAndSettle(answer(WalletChainPolicy.DEFAULT_CHAIN_ID, "eip155:421614"))

        assertEquals(WalletChainPolicy.DEFAULT_CHAIN_ID, WalletSigningPlugin.sessionStore()!!.loadChainId())
        verify(connector, times(1)).setChain(WalletChainPolicy.DEFAULT_CHAIN_ID)
        verify(connector, never()).setChain("eip155:1")
        assertEquals("Sepolia Testnet", activity.spinner().selectedItem)
    }

    @Test
    fun probeAnswerIncludingSessionChain_doesNotRemap() {
        registerWithEffectiveProjectId()
        WalletSigningPlugin.sessionStore()!!.saveChainId("eip155:421614")
        val connector = probeConnector()
        installActiveConnector(connector)
        val probe = installHeldProbe()
        val activity = launch()

        probe.answers.single().deliverAndSettle(answer(WalletChainPolicy.DEFAULT_CHAIN_ID, "eip155:421614"))

        assertEquals("eip155:421614", WalletSigningPlugin.sessionStore()!!.loadChainId())
        // The chokepoint pushes the same chain back; the idempotent connector makes that free.
        verify(connector, times(1)).setChain("eip155:421614")
        assertEquals("Arbitrum Sepolia", activity.spinner().selectedItem)
    }

    @Test
    fun togglingSponsorshipOff_cancelsProbeJob() {
        registerWithEffectiveProjectId()
        WalletSigningPlugin.sessionStore()!!.saveChainId("eip155:1")
        val connector = probeConnector()
        installActiveConnector(connector)
        val probe = installHeldProbe()
        val activity = launch()

        val supersededJob = probe.jobs.single()
        activity.findViewById<SwitchMaterial>(R.id.switch_sponsor_transactions).isChecked = false
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(supersededJob.isCancelled)
        // The cancelled job cannot reach the post-probe sync, so no push follows its answer.
        probe.answers.single().deliver(answer(WalletChainPolicy.DEFAULT_CHAIN_ID))
        settle()

        verify(connector, never()).setChain(any())
    }

    @Test
    fun latestRefreshWins_outOfOrderAnswerNeitherRendersNorWritesCache() {
        configureWithBuildProjectId("replace-me-placeholder")
        WalletSigningPlugin.register(context)
        val store = WalletSigningPlugin.sessionStore()!!
        store.saveSponsorTransactionsEnabled(true)
        val first = "550e8400-e29b-41d4-a716-446655440000"
        val second = "6ba7b810-9dad-11d1-80b4-00c04fd430c8"
        store.saveZeroDevProjectIdOverride(first)
        val seededFirst = listOf(WalletChainPolicy.DEFAULT_CHAIN_ID)
        seedCache(*seededFirst.toTypedArray())
        val probe = installHeldProbe()
        val activity = launch()
        assertEquals(listOf<Any?>("Sepolia Testnet"), activity.spinner().items())
        val supersededJob = probe.jobs.single()

        activity.findViewById<EditText>(R.id.et_zerodev_project_id).setText(second)
        activity.findViewById<Button>(R.id.btn_save_zerodev_project_id).performClick()
        // The first project's cached set must not be served under the second project's id.
        assertEquals(0, activity.spinner().adapter.count)
        assertEquals(listOf(first, second), probe.calls.map { it.first })
        for ((_, chainIds) in probe.calls) {
            assertEquals(SUPPORTED_CHAINS.map { it.caip2Id }, chainIds)
        }
        assertEquals(View.VISIBLE, activity.checkingText().visibility)
        // The superseded job is cancelled, so its answer cannot reach the cache or the post-probe sync.
        assertTrue(supersededJob.isCancelled)

        probe.answers[0].deliver(answer("eip155:1"))
        assertEquals(0, activity.spinner().adapter.count)
        assertEquals(View.VISIBLE, activity.checkingText().visibility)
        assertEquals(seededFirst, cachedChains(first))
        assertNull(cachedChains(second))

        probe.answers[1].deliverAndSettle(answer(WalletChainPolicy.DEFAULT_CHAIN_ID))
        assertEquals(1, activity.spinner().adapter.count)
        assertEquals("Sepolia Testnet", activity.spinner().adapter.getItem(0))
        assertEquals(View.GONE, activity.checkingText().visibility)
        assertEquals(listOf(WalletChainPolicy.DEFAULT_CHAIN_ID), cachedChains(second))
        // The store keeps one entry, for the latest project, so the second project's write replaced the first's.
        assertNull(cachedChains(first))
    }

    /** A "replace-me" id enables the project-id field without being usable itself. */
    private fun configureWithBuildProjectId(buildProjectId: String) {
        WalletSigningPlugin.configure(
            WalletSdkConfig(
                privyAppId = "app",
                privyAppClientId = "client",
                zeroDevProjectId = buildProjectId,
            ),
        )
    }

    @Test
    fun newIntent_withRejectedExtra_recordsRejectMessage() {
        registerWithEffectiveProjectId()
        val controller = Robolectric.buildActivity(
            WalletSettingsActivity::class.java,
            Intent(context, WalletSettingsActivity::class.java),
        )
        controller.create().start().visible()

        controller.newIntent(
            Intent().apply {
                putExtra(WalletDeepLinkContract.EXTRA_DEEP_LINK_REJECTED, true)
                putExtra(WalletDeepLinkContract.EXTRA_DEEP_LINK_MESSAGE, "Chain rejected")
            },
        )

        // The Material dialog is an appcompat AlertDialog, which ShadowAlertDialog does not track.
        val dialog = ShadowDialog.getLatestDialog() as AlertDialog
        assertTrue(dialog.isShowing)
        assertEquals("Chain rejected", dialog.findViewById<TextView>(android.R.id.message)!!.text.toString())
    }

    @Test
    fun newIntent_afterSponsorTrue_rebuildsAdapterOffFullCatalog() {
        registerWithEffectiveProjectId(sponsorshipOn = false)
        val store = WalletSigningPlugin.sessionStore()!!
        store.saveChainId("eip155:42161")
        var probeCalls = 0
        WalletSigningPlugin.sponsorshipProbe = { _, _ ->
            probeCalls++
            answer(WalletChainPolicy.DEFAULT_CHAIN_ID, "eip155:421614")
        }
        val controller = Robolectric.buildActivity(
            WalletSettingsActivity::class.java,
            Intent(context, WalletSettingsActivity::class.java),
        )
        controller.create().start().visible()
        val spinner = controller.get().spinner()
        assertEquals(SUPPORTED_CHAINS.size, spinner.adapter.count)

        store.saveSponsorTransactionsEnabled(true)
        store.saveChainId(WalletChainPolicy.DEFAULT_CHAIN_ID)
        controller.newIntent(
            Intent().apply {
                putExtra(WalletDeepLinkContract.EXTRA_DEEP_LINK_REJECTED, false)
                putExtra(WalletDeepLinkContract.EXTRA_DEEP_LINK_MESSAGE, "Updated")
            },
        )

        assertEquals(1, probeCalls)
        assertEquals(2, spinner.adapter.count)
        assertEquals("Sepolia Testnet", spinner.adapter.getItem(0))
        assertEquals(0, spinner.selectedItemPosition)
    }
}
