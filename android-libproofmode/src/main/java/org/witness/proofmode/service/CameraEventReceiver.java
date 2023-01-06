package org.witness.proofmode.service;

import android.annotation.SuppressLint;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;

import java.util.Date;

public class CameraEventReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {

        if (intent != null && intent.getData() != null)
            MediaWatcher.getInstance(context).processUri(intent.getData(), true, new Date());

    }
}
