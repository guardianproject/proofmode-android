package org.witness.proofmode

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.mutableStateListOf
import timber.log.Timber
import java.util.Date

class ProofEventReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {

        /**
        //got event
        Toast.makeText(
            context,
            R.string.progress_generating_proof,
            Toast.LENGTH_SHORT
        ).show()
            **/

        val uriMedia = intent?.getStringExtra(ProofMode.EVENT_PROOF_EXTRA_URI)
        val proofHash = intent?.getStringExtra(ProofMode.EVENT_PROOF_EXTRA_HASH)

        uriMedia?.let {

            if (context != null && proofHash != null) {

                if (Activities.getProofableItem(context, proofHash).isEmpty()) {

                    Activities.addActivity(
                        Activity(
                            it, ActivityType.MediaCaptured(
                                items = mutableStateListOf(
                                    ProofableItem(proofHash, Uri.parse(it))
                                )
                            ), Date()
                        ), context
                    )
                }
            }

            Timber.tag("ProofEventReceiver").i("New Proof Event: %s", it)
        }
    }

}