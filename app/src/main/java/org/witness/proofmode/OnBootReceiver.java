package org.witness.proofmode;

import static org.witness.proofmode.ProofMode.PREFS_DOPROOF;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.preference.PreferenceManager;

import org.witness.proofmode.ProofModeApp;

/**
 * Created by n8fr8 on 10/10/16.
 */
public class OnBootReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {

        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);

        if (prefs.getBoolean(PREFS_DOPROOF, false)) {


            Intent intentService = new Intent(context, ProofService.class);

            if (Build.VERSION.SDK_INT >= 26) {
                context.startForegroundService(intentService);

            } else {
                context.startService(intentService);
            }

        }
    }
}
