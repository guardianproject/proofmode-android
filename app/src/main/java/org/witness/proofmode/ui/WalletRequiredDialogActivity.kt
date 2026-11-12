package org.witness.proofmode.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.addCallback
import androidx.appcompat.app.AppCompatActivity
import org.witness.proofmode.FeatureFlags
import org.witness.proofmode.R
import org.witness.proofmode.plugins.lp.autocapture.AutoCaptureLpMode
import org.witness.proofmode.plugins.lp.wallet.WalletSettingsActivity

/**
 * Shown after an in-app capture when auto-attest is on and no wallet is connected. It is an activity
 * rather than a dialog on the current screen because the camera lives in a module that cannot reach
 * `:app`, and an activity keeps its choice across rotation. A choice is required: Back means
 * "Turn off auto-attest" so the easy path ends with the setting off.
 */
class WalletRequiredDialogActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_wallet_required_dialog)

        findViewById<android.view.View>(R.id.wallet_required_connect).setOnClickListener {
            startActivity(Intent(this, WalletSettingsActivity::class.java))
            finish()
        }
        findViewById<android.view.View>(R.id.wallet_required_turn_off).setOnClickListener {
            turnOffAutoAttest()
        }
        onBackPressedDispatcher.addCallback(this) { turnOffAutoAttest() }
    }

    private fun turnOffAutoAttest() {
        FeatureFlags.autoCaptureLpMode = AutoCaptureLpMode.OFF
        finish()
    }

    companion object {
        fun launch(context: Context) {
            context.startActivity(
                Intent(context, WalletRequiredDialogActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }
}
