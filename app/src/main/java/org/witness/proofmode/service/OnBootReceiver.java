package org.witness.proofmode.service;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * Created by n8fr8 on 10/10/16.
 */
public class OnBootReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {

        if (android.os.Build.VERSION.SDK_INT >= 24) {
            //do nothing; the contentjobs are started in the app onCreate() right?
        }
        else
        {
            context.startService(new Intent(context, MediaListenerService.class));
        }
    }
}
