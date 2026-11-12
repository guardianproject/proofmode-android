package org.witness.proofmode.plugins.lp.wallet

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.ImageButton
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.IdRes
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.switchmaterial.SwitchMaterial
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.witness.proofmode.plugins.lp.R
import org.witness.proofmode.plugins.lp.LocationProtocolPlugin
import org.witness.proofmode.plugins.lp.config.ChainConfig
import org.witness.proofmode.plugins.lp.config.ChainSpinnerPolicy
import org.witness.proofmode.plugins.lp.config.SUPPORTED_CHAINS
import org.witness.proofmode.plugins.lp.config.easScanUrl
import org.witness.proofmode.plugins.lp.config.explorerUrl
import org.witness.proofmode.plugins.lp.config.walletExplorerLinkVisibility
import org.witness.proofmode.plugins.lp.wallet.auth.WalletAuthBottomSheet
import org.witness.proofmode.plugins.lp.wallet.auth.WalletOnboardingPreferences
import org.witness.proofmode.plugins.lp.deeplink.WalletDeepLinkContract
import org.witness.proofmode.plugins.lp.wallet.WalletSponsorshipSettingsPresenter
import org.witness.proofmode.plugins.wallet.infra.abbreviateAddress
import org.witness.proofmode.plugins.wallet.infra.config.SponsoredSetState
import org.witness.proofmode.plugins.wallet.infra.config.ZeroDevConfigResolver
import org.witness.proofmode.plugins.wallet.infra.factory.WalletProviderFactory
import org.witness.proofmode.plugins.wallet.infra.factory.WalletSessionStore
import org.witness.proofmode.plugins.wallet.infra.model.WalletAuthenticating
import org.witness.proofmode.plugins.wallet.infra.model.WalletAwaitingNetwork
import org.witness.proofmode.plugins.wallet.infra.model.WalletConnected
import org.witness.proofmode.plugins.wallet.infra.model.WalletConnecting
import org.witness.proofmode.plugins.wallet.infra.model.WalletCreatingWallet
import org.witness.proofmode.plugins.wallet.infra.model.WalletUnavailable
import org.witness.proofmode.plugins.wallet.infra.api.WalletConnector
import org.witness.proofmode.plugins.wallet.infra.model.WalletDisconnected
import org.witness.proofmode.util.ImeScrollPadding
import timber.log.Timber

class WalletSettingsActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "WalletSettings"
    }

    private val activeWalletConnector: WalletConnector
        get() = WalletSigningPlugin.providerSelection.activeConnector

    private var visibleChains: List<ChainConfig> = SUPPORTED_CHAINS
    private var sponsoredSet: SponsoredSetState = SponsoredSetState.NotYetKnown
    private var sponsorshipProbeJob: Job? = null
    private var selectedChainIndex: Int = 0
    private lateinit var spinnerChain: Spinner
    private lateinit var sessionStore: WalletSessionStore
    private var sponsorshipRefresh: (() -> Unit)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!LocationProtocolPlugin.isWalletStackRegistered()) {
            Timber.w("WalletSettings: wallet stack not registered — finishing")
            finish()
            return
        }
        sessionStore = checkNotNull(WalletSigningPlugin.sessionStore())
        setContentView(R.layout.activity_wallet_settings)
        ImeScrollPadding.bind(findViewById(R.id.scroll_wallet_settings))

        setSupportActionBar(findViewById(R.id.toolbar))
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.setDisplayShowHomeEnabled(true)
        supportActionBar?.title = ""

        supportFragmentManager.setFragmentResultListener(
            WalletAuthBottomSheet.RESULT_KEY,
            this,
        ) { _, result ->
            val error = result.getString(WalletAuthBottomSheet.RESULT_ERROR_KEY)
            if (error == null) {
                Toast.makeText(this, R.string.wallet_authenticated, Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, error, Toast.LENGTH_SHORT).show()
            }
        }

        val tvAddress = findViewById<TextView>(R.id.tv_wallet_address)
        val btnConnect = findViewById<Button>(R.id.btn_connect_wallet)
        val btnDisconnect = findViewById<Button>(R.id.btn_disconnect_wallet)
        val btnRetryWallet = findViewById<Button>(R.id.btn_retry_wallet)
        val btnCopyAddress = findViewById<ImageButton>(R.id.btn_copy_address)
        val cardExplorerLinks = findViewById<MaterialCardView>(R.id.card_explorer_links)
        spinnerChain = findViewById(R.id.spinner_chain)

        val initialChainId = sessionStore.loadChainId()
        refreshSponsoredChains(initialChainId)

        spinnerChain.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val newChain = visibleChains.getOrNull(position) ?: return
                if (position == selectedChainIndex) return

                selectedChainIndex = position
                applyExplorerLinkVisibility()
                val selection = WalletSigningPlugin.providerSelection
                lifecycleScope.launch {
                    try {
                        WalletProviderFactory.syncSessionChain(
                            selection = selection,
                            sessionStore = sessionStore,
                            buildProjectId = WalletSigningPlugin.buildDefaultZeroDevProjectId(),
                            requestedChainId = newChain.caip2Id,
                        )
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Timber.w(e, "setChain failed")
                        Toast.makeText(
                            this@WalletSettingsActivity,
                            e.message ?: "Network switch failed",
                            Toast.LENGTH_LONG,
                        ).show()
                    }
                }
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        sponsorshipRefresh = configureSponsorshipSection()
        infoButton(R.id.btn_info_sponsorship, R.string.info_sponsorship_title, R.string.info_sponsorship_body)

        // Observe Wallet State
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                activeWalletConnector.stateFlow.collect { state ->
                    // Only the text `when` is exhaustive; a new WalletState must also be classified in the three flags.
                    val connected = state is WalletConnected
                    val busy = state is WalletConnecting || state is WalletCreatingWallet || state is WalletAuthenticating
                    // canConnect: connect button shown (disabled while busy), disconnect hidden.
                    val canConnect = busy || state is WalletDisconnected
                    when (state) {
                        is WalletConnected -> tvAddress.text = connectedAddressText(state.identity.address)
                        is WalletDisconnected -> tvAddress.setText(R.string.wallet_not_connected)
                        is WalletConnecting -> tvAddress.setText(R.string.wallet_connecting)
                        is WalletCreatingWallet -> tvAddress.setText(R.string.wallet_creating)
                        is WalletUnavailable -> tvAddress.setText(R.string.wallet_unavailable)
                        // Saved session not yet verified. The connector asks Privy to verify it
                        // (onNetworkRestored) when the network returns and on each onStart.
                        is WalletAwaitingNetwork -> tvAddress.setText(R.string.wallet_awaiting_network)
                        is WalletAuthenticating -> tvAddress.setText(R.string.wallet_authenticating)
                    }
                    btnConnect.visibility = visibleWhen(canConnect)
                    btnConnect.isEnabled = !busy
                    btnDisconnect.visibility = visibleWhen(!canConnect)
                    btnRetryWallet.visibility = visibleWhen(state is WalletUnavailable)
                    btnCopyAddress.visibility = visibleWhen(connected)
                    cardExplorerLinks.visibility = visibleWhen(connected)
                    if (state is WalletConnected) {
                        val index = visibleChains.indexOfFirst { it.caip2Id == state.identity.chainId }
                        if (index < 0) {
                            bindChainSpinner(state.identity.chainId)
                        } else if (index != selectedChainIndex) {
                            selectedChainIndex = index
                            applyExplorerLinkVisibility()
                            spinnerChain.setSelection(index, false)
                        }
                    }
                }
            }
        }

        btnConnect.setOnClickListener {
            showWalletAuthBottomSheet()
        }

        findViewById<Button>(R.id.btn_show_onboarding_guide).setOnClickListener {
            showOnboardingTour()
        }

        btnRetryWallet.setOnClickListener {
            btnRetryWallet.isEnabled = false
            lifecycleScope.launch {
                try {
                    activeWalletConnector.retryWallet()
                } catch (e: CancellationException) {
                    throw e
                } catch (t: Throwable) {
                    Timber.tag(TAG).w(t, "retryWallet failed")
                    Toast.makeText(
                        this@WalletSettingsActivity,
                        t.message ?: getString(R.string.wallet_unavailable),
                        Toast.LENGTH_SHORT,
                    ).show()
                } finally {
                    btnRetryWallet.isEnabled = true
                }
            }
        }

        findViewById<Button>(R.id.btn_retry_sponsored_networks).setOnClickListener {
            refreshSponsoredChains(sessionStore.loadChainId())
        }

        btnDisconnect.setOnClickListener {
            lifecycleScope.launch { activeWalletConnector.disconnect() }
        }

        btnCopyAddress.setOnClickListener {
            val address = activeWalletConnector.getIdentity()?.address
            if (!address.isNullOrBlank()) {
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val clip = ClipData.newPlainText("wallet_address", address)
                clipboard.setPrimaryClip(clip)
                Toast.makeText(this, R.string.wallet_address_copied, Toast.LENGTH_SHORT).show()
            }
        }

        findViewById<Button>(R.id.btn_view_transactions).setOnClickListener {
            openAddressLink(ChainConfig::explorerUrl)
        }
        findViewById<Button>(R.id.btn_view_attestations).setOnClickListener {
            openAddressLink(ChainConfig::easScanUrl)
        }

        infoButton(R.id.btn_info_web3_wallet, R.string.info_web3_wallet_title, R.string.info_web3_wallet_body)
        infoButton(R.id.btn_info_network, R.string.info_network_title, R.string.info_network_body)
        infoButton(R.id.btn_info_privy, R.string.info_privy_title, R.string.info_privy_body)

        handleDeepLinkExtras(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        refreshSponsoredChains(sessionStore.loadChainId())
        sponsorshipRefresh?.invoke()
        handleDeepLinkExtras(intent)
    }

    private fun handleDeepLinkExtras(intent: Intent) {
        if (!intent.hasExtra(WalletDeepLinkContract.EXTRA_DEEP_LINK_REJECTED) &&
            intent.getStringExtra(WalletDeepLinkContract.EXTRA_DEEP_LINK_MESSAGE) == null
        ) {
            return
        }

        val rejected = intent.getBooleanExtra(WalletDeepLinkContract.EXTRA_DEEP_LINK_REJECTED, false)
        val message = intent.getStringExtra(WalletDeepLinkContract.EXTRA_DEEP_LINK_MESSAGE)

        if (rejected) {
            MaterialAlertDialogBuilder(this)
                .setMessage(message ?: getString(R.string.wallet_deep_link_chain_rejected))
                .setPositiveButton(R.string.close, null)
                .show()
            return
        }

        val snackbarMessage = message ?: getString(R.string.wallet_deep_link_applied_summary)
        Snackbar.make(findViewById(android.R.id.content), snackbarMessage, Snackbar.LENGTH_LONG).show()
    }

    override fun onStart() {
        super.onStart()
        val connector = activeWalletConnector
        connector.bindActivity(this)
        // Manual trigger for a session still awaiting verification; a no-op in any other state.
        lifecycleScope.launch { connector.refreshSession() }
    }

    override fun onStop() {
        super.onStop()
        activeWalletConnector.unbindActivity()
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressedDispatcher.onBackPressed()
        return true
    }

    private fun infoButton(@IdRes buttonId: Int, @StringRes titleRes: Int, @StringRes bodyRes: Int) {
        findViewById<ImageButton>(buttonId).setOnClickListener {
            MaterialAlertDialogBuilder(this)
                .setTitle(titleRes)
                .setMessage(bodyRes)
                .setPositiveButton(R.string.close, null)
                .show()
        }
    }

    private fun openAddressLink(urlFor: ChainConfig.(String) -> String?) {
        val identity = activeWalletConnector.getIdentity() ?: return
        val config = visibleChains.getOrNull(selectedChainIndex) ?: return
        val url = config.urlFor(identity.address) ?: return
        runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
    }

    private fun showOnboardingTour() {
        WalletOnboardingPreferences(this).clearSkipPreference()
        val sheet = WalletAuthBottomSheet.newOnboardingTour()
        sheet.show(supportFragmentManager, WalletAuthBottomSheet.TAG)
    }

    private fun showWalletAuthBottomSheet() {
        val sheet = WalletAuthBottomSheet.newConnectFlow()
        sheet.show(supportFragmentManager, WalletAuthBottomSheet.TAG)
    }

    private fun visibleWhen(shown: Boolean) = if (shown) View.VISIBLE else View.GONE

    private fun connectedAddressText(address: String): String =
        getString(R.string.wallet_address_connected, abbreviateAddress(address))

    // Re-applied after every selectedChainIndex write; the spinner listener skips programmatic selections.
    private fun applyExplorerLinkVisibility() {
        val visibility = walletExplorerLinkVisibility(visibleChains.getOrNull(selectedChainIndex))
        findViewById<Button>(R.id.btn_view_transactions).visibility =
            if (visibility.transactions) View.VISIBLE else View.GONE
        findViewById<Button>(R.id.btn_view_attestations).visibility =
            if (visibility.attestations) View.VISIBLE else View.GONE
    }

    private fun effectiveProjectId(): String {
        val buildDefault = WalletSigningPlugin.buildDefaultZeroDevProjectId()
        return ZeroDevConfigResolver.effectiveProjectId(
            sessionOverride = sessionStore.loadZeroDevProjectIdOverride(),
            buildProjectId = buildDefault,
        )
    }

    private fun isSponsorshipToggleOn(): Boolean =
        sessionStore.isSponsorTransactionsEnabled()

    /** The project id to probe, or null when the spinner does not depend on a probe. */
    private fun probeProjectId(): String? {
        val projectId = effectiveProjectId()
        val probe = ChainSpinnerPolicy.shouldProbe(
            sponsorshipOn = isSponsorshipToggleOn(),
            hasEffectiveProjectId = ZeroDevConfigResolver.isConfiguredSecret(projectId),
        )
        return projectId.takeIf { probe }
    }

    // Cancelling the previous launch keeps an answer for a superseded project id or toggle
    // state from reaching the spinner or the cache. The probe is not launched from
    // bindChainSpinner because the wallet-state collector re-renders on every emission.
    private fun refreshSponsoredChains(savedChainId: String?) {
        val projectId = probeProjectId()
        sponsorshipProbeJob?.cancel()
        sponsoredSet = cachedSponsoredSet(projectId)
        bindChainSpinner(savedChainId)
        if (projectId == null) return

        sponsorshipProbeJob = lifecycleScope.launch {
            val answer = WalletSigningPlugin.sponsorshipProbe(projectId, SUPPORTED_CHAINS.map { it.caip2Id })
            val cacheWritten = applyProbeAnswer(projectId, answer)
            bindChainSpinner(sessionStore.loadChainId())
            if (!cacheWritten) return@launch
            syncSessionChainAfterProbe()
            bindChainSpinner(sessionStore.loadChainId())
        }
    }

    // Runs inside the probe job: a superseded job is cancelled before the sync's IO block, so a
    // stale answer never remaps.
    private suspend fun syncSessionChainAfterProbe() {
        try {
            WalletProviderFactory.syncSessionChain(
                selection = WalletSigningPlugin.providerSelection,
                sessionStore = sessionStore,
                buildProjectId = WalletSigningPlugin.buildDefaultZeroDevProjectId(),
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Session chain sync after probe failed")
        }
    }

    private fun cachedSponsoredSet(projectId: String?): SponsoredSetState {
        val cached = projectId?.let { sessionStore.loadSponsoredChains(it) }
        return cached?.let { SponsoredSetState.Known(it) } ?: SponsoredSetState.NotYetKnown
    }

    /** Returns true when the answer was usable and its set is now cached. */
    private fun applyProbeAnswer(projectId: String, sponsored: List<String>?): Boolean {
        if (sponsored != null) {
            sessionStore.saveSponsoredChains(projectId, sponsored)
            sponsoredSet = SponsoredSetState.Known(sponsored)
            return true
        }
        if (sponsoredSet == SponsoredSetState.NotYetKnown) {
            // A failure with a cache keeps showing the cache.
            sponsoredSet = SponsoredSetState.CheckFailed
        }
        return false
    }

    private fun bindChainSpinner(savedChainId: String?) {
        val toggleOn = isSponsorshipToggleOn()
        val hasProject = ZeroDevConfigResolver.isConfiguredSecret(effectiveProjectId())
        visibleChains = ChainSpinnerPolicy.visibleChains(
            sponsorshipOn = toggleOn,
            hasEffectiveProjectId = hasProject,
            sponsoredSet = sponsoredSet,
        )
        val adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            visibleChains.map { it.displayName },
        )
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerChain.adapter = adapter
        spinnerChain.isEnabled = visibleChains.isNotEmpty()
        val note = ChainSpinnerPolicy.note(
            sponsorshipOn = toggleOn,
            hasEffectiveProjectId = hasProject,
            sponsoredSet = sponsoredSet,
        )
        findViewById<TextView>(R.id.tv_empty_sponsored_networks).visibility =
            visibleWhen(note == ChainSpinnerPolicy.NoteState.NoSponsored)
        findViewById<TextView>(R.id.tv_checking_sponsored_networks).visibility =
            visibleWhen(note == ChainSpinnerPolicy.NoteState.Checking)
        findViewById<TextView>(R.id.tv_could_not_check_sponsored_networks).visibility =
            visibleWhen(note == ChainSpinnerPolicy.NoteState.CouldNotCheck)
        findViewById<Button>(R.id.btn_retry_sponsored_networks).visibility =
            visibleWhen(note == ChainSpinnerPolicy.NoteState.CouldNotCheck)

        val index = ChainSpinnerPolicy.defaultSelectionIndex(visibleChains, savedChainId)
        selectedChainIndex = index
        applyExplorerLinkVisibility()
        if (visibleChains.isNotEmpty()) {
            spinnerChain.setSelection(index, false)
        }
    }

    private fun configureSponsorshipSection(): () -> Unit {
        val buildDefault = WalletSigningPlugin.buildDefaultZeroDevProjectId()
        val presenter = WalletSponsorshipSettingsPresenter()

        val switchSponsor = findViewById<SwitchMaterial>(R.id.switch_sponsor_transactions)
        val tvSummary = findViewById<TextView>(R.id.tv_sponsor_transactions_summary)
        val tilProjectId = findViewById<TextInputLayout>(R.id.til_zerodev_project_id)
        val etProjectId = findViewById<TextInputEditText>(R.id.et_zerodev_project_id)
        val btnSave = findViewById<Button>(R.id.btn_save_zerodev_project_id)

        val hasBuildDefault = buildDefault.isNotBlank()
        etProjectId.isEnabled = hasBuildDefault
        btnSave.isEnabled = hasBuildDefault

        fun bindProjectIdUi() {
            val override = sessionStore.loadZeroDevProjectIdOverride()
            etProjectId.setText(presenter.displayProjectId(override, buildDefault))
            tilProjectId.error = null
        }

        fun bindToggleUi() {
            val enabled = sessionStore.isSponsorTransactionsEnabled()
            switchSponsor.isChecked = enabled
            tvSummary.setText(
                if (enabled) R.string.wallet_sponsor_transactions_summary_on
                else R.string.wallet_sponsor_transactions_summary_off,
            )
        }

        bindToggleUi()
        bindProjectIdUi()

        etProjectId.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus && hasBuildDefault && etProjectId.text.isNullOrBlank()) {
                etProjectId.setText(buildDefault)
                etProjectId.setSelection(etProjectId.text?.length ?: 0)
            }
        }

        switchSponsor.setOnCheckedChangeListener { _, isChecked ->
            if (sessionStore.isSponsorTransactionsEnabled() == isChecked) return@setOnCheckedChangeListener
            sessionStore.saveSponsorTransactionsEnabled(isChecked)
            bindToggleUi()
            refreshSponsoredChains(sessionStore.loadChainId())
        }

        fun saveProjectId() {
            val raw = etProjectId.text?.toString().orEmpty()
            val validation = presenter.validateProjectIdInput(raw)
            if (!validation.isValid) {
                tilProjectId.error = getString(R.string.wallet_zerodev_project_id_error_invalid)
                return
            }
            val newOverride = presenter.resolvePersistedOverride(raw, buildDefault)
            if (newOverride == sessionStore.loadZeroDevProjectIdOverride()) return

            sessionStore.saveZeroDevProjectIdOverride(newOverride)
            bindProjectIdUi()
            refreshSponsoredChains(sessionStore.loadChainId())
            Toast.makeText(this, R.string.wallet_zerodev_project_id_saved, Toast.LENGTH_SHORT).show()
        }

        btnSave.setOnClickListener { saveProjectId() }
        etProjectId.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                saveProjectId()
                true
            } else {
                false
            }
        }

        return {
            bindToggleUi()
            bindProjectIdUi()
        }
    }

}
