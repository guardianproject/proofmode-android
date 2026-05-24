package org.witness.proofmode.org.witness.proofmode.util

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import org.witness.proofmode.org.witness.proofmode.ui.Activities
import org.witness.proofmode.ProofMode
import timber.log.Timber

class ProofEventReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {

        if (context == null || intent == null) return

        val uriMedia = intent.getStringExtra(ProofMode.EVENT_PROOF_EXTRA_URI) ?: return
        val proofHash = intent.getStringExtra(ProofMode.EVENT_PROOF_EXTRA_HASH)

        when (intent.action) {
            // Capture written: show it in the feed immediately as PENDING.
            ProofMode.EVENT_MEDIA_CAPTURED ->
                Activities.addCapturedPending(uriMedia, context)

            // Signing started: advance the existing item to GENERATING.
            ProofMode.EVENT_PROOF_START ->
                Activities.markProofGenerating(uriMedia, context)

            // Proof complete: flip the item to GENERATED with its real hash, or
            // create it if this URI was never seen (e.g. gallery import).
            ProofMode.EVENT_PROOF_GENERATED,
            ProofMode.EVENT_PROOF_GENERATED_IMPORT -> {
                if (proofHash != null) {
                    val imported = intent.action == ProofMode.EVENT_PROOF_GENERATED_IMPORT
                    Activities.markProofGenerated(uriMedia, proofHash, imported, context)
                }
            }
        }

        Timber.tag("ProofEventReceiver").i("Proof Event %s: %s", intent.action, uriMedia)
    }

}