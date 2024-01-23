package org.witness.proofmode

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.runtime.mutableStateListOf
import timber.log.Timber
import java.util.Date
import java.util.UUID

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

        var uriMedia = intent?.getStringExtra(ProofMode.EVENT_PROOF_EXTRA_URI)?.let {

            if (context != null) {
                Activities.addActivity(
                    Activity(
                        it, ActivityType.MediaCaptured(
                            items = mutableStateListOf(
                                ProofableItem(it, Uri.parse(it))
                            )
                        ), Date()
                    ), context
                )
            }

            Timber.d("New Proof Event: " + it)
        }
    }

}