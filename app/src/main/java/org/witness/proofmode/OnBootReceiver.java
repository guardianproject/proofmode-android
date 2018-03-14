package org.witness.proofmode;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import org.witness.proofmode.ProofModeApp;

/**
 * Created by n8fr8 on 10/10/16.
 */
public class OnBootReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {

        ProofModeApp.init(context.getApplicationContext());

    }
}
