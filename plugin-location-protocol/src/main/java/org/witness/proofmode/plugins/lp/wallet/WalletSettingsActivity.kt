package org.witness.proofmode.plugins.lp.wallet

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.inputmethod.EditorInfo
import androidx.annotation.VisibleForTesting
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.ImageButton
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.witness.proofmode.plugins.lp.R
import org.witness.proofmode.plugins.lp.LocationProtocolPlugin
import org.witness.proofmode.plugins.lp.config.ChainConfig
import org.witness.proofmode.plugins.lp.config.SUPPORTED_CHAINS
import org.witness.proofmode.plugins.lp.config.easScanUrl
import org.witness.proofmode.plugins.lp.config.explorerUrl
import org.witness.proofmode.plugins.lp.wallet.auth.WalletAuthBottomSheet
import org.witness.proofmode.plugins.lp.wallet.auth.WalletOnboardingPreferences
import org.witness.proofmode.plugins.lp.deeplink.WalletDeepLinkContract
import org.witness.proofmode.plugins.lp.wallet.WalletSponsorshipSettingsPresenter
import org.witness.proofmode.plugins.wallet.infra.BuildConfig
import org.witness.proofmode.plugins.wallet.infra.model.WalletAuthenticating
import org.witness.proofmode.plugins.wallet.infra.model.WalletConnected
import org.witness.proofmode.plugins.wallet.infra.api.WalletCapabilityProvider
import org.witness.proofmode.plugins.wallet.infra.model.WalletConnecting
import org.witness.proofmode.plugins.wallet.infra.api.WalletConnector
import org.witness.proofmode.plugins.wallet.infra.model.WalletDisconnected
import timber.log.Timber

class WalletSettingsActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "WalletSettings"

        @VisibleForTesting
        internal var lastDeepLinkRejectMessageForTests: String? = null
    }

    private val activeWalletConnector: WalletConnector
        get() = WalletSigningPlugin.providerSelection.activeConnector

    private val chainMapping: List<ChainConfig> = SUPPORTED_CHAINS
    private var selectedChainIndex: Int = 0
    private lateinit var spinnerChain: Spinner
    private var sponsorshipRefresh: (() -> Unit)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!LocationProtocolPlugin.isWalletStackRegistered()) {
            Timber.w("WalletSettings: wallet stack not registered — finishing")
            finish()
            return
        }
        setContentView(R.layout.activity_wallet_settings)

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
        val btnCopyAddress = findViewById<ImageButton>(R.id.btn_copy_address)
        val btnInfoWeb3 = findViewById<ImageButton>(R.id.btn_info_web3_wallet)
        val btnInfoEas = findViewById<ImageButton>(R.id.btn_info_eas)
        val btnInfoPrivy = findViewById<ImageButton>(R.id.btn_info_privy)
        val cardExplorerLinks = findViewById<MaterialCardView>(R.id.card_explorer_links)
        spinnerChain = findViewById(R.id.spinner_chain)

        val deepLinkAppliedChain =
            intent.getStringExtra(WalletDeepLinkContract.EXTRA_DEEP_LINK_APPLIED_CHAIN)
        val storeChain = WalletSigningPlugin.sessionStore()?.loadChainId()
        val initialChainId = when {
            deepLinkAppliedChain != null -> deepLinkAppliedChain
            storeChain != null -> storeChain
            else -> activeWalletConnector.getIdentity()?.chainId ?: "eip155:1"
        }
        val initialIndex = chainMapping.indexOfFirst { it.caip2Id == initialChainId }.coerceAtLeast(0)
        selectedChainIndex = initialIndex

        val adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            chainMapping.map { it.displayName }
        )
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerChain.adapter = adapter
        spinnerChain.setSelection(initialIndex, false)

        val connectorType = activeWalletConnector.javaClass.simpleName
        Timber.tag(TAG).d(
            "Wallet settings opened: chainId=%s connector=%s address=%s sponsorshipActive=%s",
            initialChainId,
            connectorType,
            abbreviateAddress(activeWalletConnector.getIdentity()?.address ?: "n/a"),
            (activeWalletConnector as? WalletCapabilityProvider)?.isSponsorshipActive,
        )

        spinnerChain.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val previousChain = chainMapping.getOrNull(selectedChainIndex)?.caip2Id
                val newChain = chainMapping[position]
                if (position == selectedChainIndex && previousChain == newChain.caip2Id) return

                selectedChainIndex = position
                val connector = activeWalletConnector
                Timber.tag(TAG).i(
                    "Chain switch requested: %s → %s connector=%s address=%s",
                    previousChain,
                    newChain.caip2Id,
                    connector.javaClass.simpleName,
                    abbreviateAddress(connector.getIdentity()?.address ?: "n/a"),
                )
                lifecycleScope.launch {
                    try {
                        withContext(Dispatchers.IO) {
                            connector.setChain(newChain.caip2Id)
                        }
                    } catch (e: Exception) {
                        Timber.w(e, "setChain failed")
                        Toast.makeText(
                            this@WalletSettingsActivity,
                            e.message ?: "Network switch failed",
                            Toast.LENGTH_LONG,
                        ).show()
                    }
                }
                Timber.tag(TAG).d(
                    "Chain switch result: chainId=%s sponsorshipActive=%s connectorAddress=%s",
                    newChain.caip2Id,
                    (connector as? WalletCapabilityProvider)?.isSponsorshipActive,
                    abbreviateAddress(connector.getIdentity()?.address ?: "n/a"),
                )
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        sponsorshipRefresh = configureSponsorshipSection()
        findViewById<ImageButton>(R.id.btn_info_sponsorship).setOnClickListener {
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.info_sponsorship_title)
                .setMessage(R.string.info_sponsorship_body)
                .setPositiveButton(R.string.close, null)
                .show()
        }

        // Observe Wallet State
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                activeWalletConnector.stateFlow.collect { state ->
                    when (state) {
                        is WalletConnected -> {
                            tvAddress.text = abbreviateAddress(state.identity.address)
                            btnConnect.visibility = View.GONE
                            btnDisconnect.visibility = View.VISIBLE
                            btnCopyAddress.visibility = View.VISIBLE
                            cardExplorerLinks.visibility = View.VISIBLE
                            btnConnect.isEnabled = true

                            val index = chainMapping.indexOfFirst { it.caip2Id == state.identity.chainId }
                            if (index >= 0 && index != selectedChainIndex) {
                                val previousChain = chainMapping.getOrNull(selectedChainIndex)?.caip2Id
                                Timber.tag(TAG).d(
                                    "Chain synced from wallet state: %s → %s address=%s sponsorshipActive=%s",
                                    previousChain,
                                    state.identity.chainId,
                                    abbreviateAddress(state.identity.address),
                                    (activeWalletConnector as? WalletCapabilityProvider)?.isSponsorshipActive,
                                )
                                selectedChainIndex = index
                                spinnerChain.setSelection(index, false)
                            }
                        }
                        is WalletDisconnected -> {
                            tvAddress.setText(R.string.wallet_not_connected)
                            btnConnect.visibility = View.VISIBLE
                            btnDisconnect.visibility = View.GONE
                            btnCopyAddress.visibility = View.GONE
                            cardExplorerLinks.visibility = View.GONE
                            btnConnect.isEnabled = true
                        }
                        is WalletConnecting -> {
                            tvAddress.setText(R.string.wallet_connecting)
                            btnConnect.visibility = View.VISIBLE
                            btnDisconnect.visibility = View.GONE
                            btnCopyAddress.visibility = View.GONE
                            cardExplorerLinks.visibility = View.GONE
                            btnConnect.isEnabled = false
                        }
                        is WalletAuthenticating -> {
                            tvAddress.setText(R.string.wallet_authenticating)
                            btnConnect.visibility = View.VISIBLE
                            btnDisconnect.visibility = View.GONE
                            btnCopyAddress.visibility = View.GONE
                            cardExplorerLinks.visibility = View.GONE
                            btnConnect.isEnabled = false
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
            val identity = activeWalletConnector.getIdentity() ?: return@setOnClickListener
            val config = chainMapping.getOrNull(selectedChainIndex) ?: return@setOnClickListener
            runCatching {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(config.explorerUrl(identity.address)))
                startActivity(intent)
            }
        }

        findViewById<Button>(R.id.btn_view_attestations).setOnClickListener {
            val identity = activeWalletConnector.getIdentity() ?: return@setOnClickListener
            val config = chainMapping.getOrNull(selectedChainIndex) ?: return@setOnClickListener
            runCatching {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(config.easScanUrl(identity.address)))
                startActivity(intent)
            }
        }

        btnInfoWeb3.setOnClickListener {
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.info_web3_wallet_title)
                .setMessage(R.string.info_web3_wallet_body)
                .setPositiveButton(R.string.close, null)
                .show()
        }

        btnInfoEas.setOnClickListener {
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.info_eas_title)
                .setMessage(R.string.info_eas_body)
                .setPositiveButton(R.string.close, null)
                .show()
        }

        btnInfoPrivy.setOnClickListener {
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.info_privy_title)
                .setMessage(R.string.info_privy_body)
                .setPositiveButton(R.string.close, null)
                .show()
        }

        handleDeepLinkExtras(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleDeepLinkExtras(intent)
    }

    internal fun handleDeepLinkExtras(intent: Intent) {
        if (!intent.hasExtra(WalletDeepLinkContract.EXTRA_DEEP_LINK_REJECTED) &&
            intent.getStringExtra(WalletDeepLinkContract.EXTRA_DEEP_LINK_MESSAGE) == null
        ) {
            return
        }

        val rejected = intent.getBooleanExtra(WalletDeepLinkContract.EXTRA_DEEP_LINK_REJECTED, false)
        val message = intent.getStringExtra(WalletDeepLinkContract.EXTRA_DEEP_LINK_MESSAGE)

        refreshChainSpinnerFromSession()
        sponsorshipRefresh?.invoke()

        if (rejected) {
            lastDeepLinkRejectMessageForTests = message
            MaterialAlertDialogBuilder(this)
                .setMessage(message ?: getString(R.string.wallet_deep_link_chain_rejected))
                .setPositiveButton(R.string.close, null)
                .show()
            return
        }

        val snackbarMessage = message ?: getString(R.string.wallet_deep_link_applied_summary)
        Snackbar.make(findViewById(android.R.id.content), snackbarMessage, Snackbar.LENGTH_LONG).show()
    }

    private fun refreshChainSpinnerFromSession() {
        val appliedChain = intent.getStringExtra(WalletDeepLinkContract.EXTRA_DEEP_LINK_APPLIED_CHAIN)
        val chainId = appliedChain
            ?: WalletSigningPlugin.sessionStore()?.loadChainId()
            ?: return
        val index = chainMapping.indexOfFirst { it.caip2Id == chainId }.takeIf { it >= 0 } ?: return
        if (index == selectedChainIndex) return
        selectedChainIndex = index
        if (::spinnerChain.isInitialized) {
            spinnerChain.setSelection(index, false)
        }
    }

    override fun onStart() {
        super.onStart()
        activeWalletConnector.bindActivity(this)
    }

    override fun onStop() {
        super.onStop()
        activeWalletConnector.unbindActivity()
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressedDispatcher.onBackPressed()
        return true
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

    private fun abbreviateAddress(address: String): String {
        if (address.length <= 12) return address
        return "${address.take(6)}…${address.takeLast(4)}"
    }

    private fun configureSponsorshipSection(): (() -> Unit)? {
        val card = findViewById<MaterialCardView>(R.id.card_sponsorship)
        if (!BuildConfig.FEATURE_SPONSORSHIP_ENABLED) {
            card.visibility = View.GONE
            return null
        }

        val sessionStore = WalletSigningPlugin.sessionStore()
            ?: run {
                card.visibility = View.GONE
                return null
            }
        val buildDefault = WalletSigningPlugin.buildDefaultZeroDevProjectId()
        val presenter = WalletSponsorshipSettingsPresenter()

        val switchSponsor = findViewById<SwitchMaterial>(R.id.switch_sponsor_transactions)
        val tvSummary = findViewById<TextView>(R.id.tv_sponsor_transactions_summary)
        val tilProjectId = findViewById<TextInputLayout>(R.id.til_zerodev_project_id)
        val etProjectId = findViewById<TextInputEditText>(R.id.et_zerodev_project_id)
        val tvHelper = findViewById<TextView>(R.id.tv_zerodev_project_id_helper)
        val btnSave = findViewById<Button>(R.id.btn_save_zerodev_project_id)

        val hasBuildDefault = buildDefault.isNotBlank()
        etProjectId.isEnabled = hasBuildDefault
        btnSave.isEnabled = hasBuildDefault

        fun bindProjectIdUi() {
            val override = sessionStore.loadZeroDevProjectIdOverride()
            etProjectId.setText(presenter.displayProjectId(override, buildDefault))
            tvHelper.text = when {
                !hasBuildDefault -> getString(R.string.wallet_zerodev_project_id_helper_no_build_default)
                override.isNullOrBlank() -> getString(R.string.wallet_zerodev_project_id_helper_default, buildDefault)
                else -> getString(R.string.wallet_zerodev_project_id_helper_override)
            }
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
            Timber.tag(TAG).d(
                "Sponsor toggle saved: enabled=%s sponsorshipActive=%s",
                isChecked,
                (activeWalletConnector as? WalletCapabilityProvider)?.isSponsorshipActive,
            )
        }

        fun saveProjectId() {
            if (!hasBuildDefault) return

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
            Toast.makeText(this, R.string.wallet_zerodev_project_id_saved, Toast.LENGTH_SHORT).show()
            Timber.tag(TAG).d(
                "Project ID saved: override=%s sponsorshipActive=%s",
                newOverride ?: "(build default)",
                (activeWalletConnector as? WalletCapabilityProvider)?.isSponsorshipActive,
            )
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
