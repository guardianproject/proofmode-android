package org.witness.proofmode.service;

import android.annotation.SuppressLint;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;

import java.util.Date;

import timber.log.Timber;

public class CameraEventReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {

        if (intent != null && intent.getData() != null) {
            MediaWatcher.getInstance(context).queueMedia(intent.getData(), true, new Date(), new MediaWatcher.QueueMediaCallback() {
                        @Override
                        public void processUriDone(String resultProofHash) {
                            Timber.d("generated hash via event: " + resultProofHash);
                        }
                    }

            );
        }
    }
}
