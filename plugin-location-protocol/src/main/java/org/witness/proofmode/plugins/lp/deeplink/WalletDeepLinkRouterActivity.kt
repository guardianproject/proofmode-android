package org.witness.proofmode.plugins.lp.deeplink

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.view.ContextThemeWrapper
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.launch
import org.witness.proofmode.plugins.lp.LocationProtocolPlugin
import org.witness.proofmode.plugins.lp.R
import org.witness.proofmode.plugins.lp.wallet.WalletSettingsActivity
import timber.log.Timber

class WalletDeepLinkRouterActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "WalletDeepLink"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (!LocationProtocolPlugin.isWalletStackRegistered()) {
            Timber.tag(TAG).i("Deep link rejected: wallet stack not registered")
            MaterialAlertDialogBuilder(
                ContextThemeWrapper(
                    this,
                    com.google.android.material.R.style.ThemeOverlay_MaterialComponents_MaterialAlertDialog,
                ),
            )
                .setMessage(R.string.wallet_deep_link_feature_disabled)
                .setPositiveButton(R.string.close) { _, _ -> finish() }
                .setOnDismissListener { finish() }
                .show()
            return
        }

        val uri = intent?.data
        if (uri == null) {
            Timber.tag(TAG).w("Deep link missing URI data")
            finish()
            return
        }

        lifecycleScope.launch {
            val result = LocationProtocolPlugin.applyWalletDeepLink(this@WalletDeepLinkRouterActivity, uri)
            Timber.tag(TAG).d(
                "Deep link apply complete: rejected=%s chain=%s",
                result.rejected,
                result.appliedChain,
            )
            val settingsIntent = Intent(this@WalletDeepLinkRouterActivity, WalletSettingsActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                .putWalletDeepLinkResult(result)
            startActivity(settingsIntent)
            finish()
        }
    }
}
