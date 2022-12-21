package org.witness.proofmode;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.os.Build;
import android.os.IBinder;

import androidx.core.app.NotificationCompat;

import org.witness.proofmode.notaries.GoogleSafetyNetNotarizationProvider;
import org.witness.proofmode.notaries.OpenTimestampsNotarizationProvider;
import org.witness.proofmode.notaries.SafetyNetCheck;
import org.witness.proofmode.notarization.NotarizationProvider;

public class ProofService extends Service {

    public final static String ACTION_START = "start";
    public final static String ACTION_UPDATE_NOTIFICATION = "notify";
    public final static String EXTRA_UPDATE_NOTIFICATION = "msg";

    public final static String ACTION_STOP = "stop";


    private void showNotification (String notifyMsg) {

            String NOTIFICATION_CHANNEL_ID = getPackageName();
            String channelName = getString(R.string.app_name);

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {

                NotificationChannel chan = new NotificationChannel(NOTIFICATION_CHANNEL_ID, channelName, NotificationManager.IMPORTANCE_NONE);
                chan.setLightColor(Color.BLUE);
                chan.setLockscreenVisibility(Notification.VISIBILITY_PRIVATE);
                NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
                assert manager != null;
                manager.createNotificationChannel(chan);
            }

            Intent notificationIntent = new Intent(this, MainActivity.class);

            PendingIntent pendingIntent = PendingIntent.getActivity(this, 0,
                    notificationIntent, PendingIntent.FLAG_IMMUTABLE);

            Notification notification = new NotificationCompat.Builder(this,NOTIFICATION_CHANNEL_ID)
                    .setOngoing(true)
                    .setSmallIcon(R.drawable.ic_notify)
                    .setContentTitle(getString(R.string.app_name))
                    .setContentText(notifyMsg)
                    .setPriority(NotificationManager.IMPORTANCE_MIN)
                    .setCategory(Notification.CATEGORY_SERVICE)
                    .setVisibility(NotificationCompat.VISIBILITY_SECRET)
                    .setContentIntent(pendingIntent).build();

            startForeground(1337, notification);

    }

    @Override
    public void onDestroy() {
        super.onDestroy();

        ProofMode.stopBackgroundService(this);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {

        if (intent.getAction()!= null) {

            if (intent.getAction().equals(ACTION_START))
            {

                showNotification(getString(R.string.waiting_proof_notify));

                //add google safetynet and opentimestamps
                addDefaultNotarizationProviders();
                ProofMode.initBackgroundService(this);

            }
            else if (intent.getAction().equals(ACTION_UPDATE_NOTIFICATION))
            {
                showNotification(intent.getStringExtra(EXTRA_UPDATE_NOTIFICATION));
            }
        }

        return START_REDELIVER_INTENT;
    }

    private void addDefaultNotarizationProviders ()
    {
        try {

            Class.forName("com.google.android.gms.safetynet.SafetyNetApi");
            SafetyNetCheck.setApiKey(getString(org.witness.proofmode.library.R.string.verification_api_key));

            //notarize and then write proof so we can include notarization response
            final GoogleSafetyNetNotarizationProvider gProvider = new GoogleSafetyNetNotarizationProvider(this);
            ProofMode.addNotarizationProvider(this, gProvider);
        }
        catch (ClassNotFoundException ce)
        {
            //SafetyNet API not available
        }

        try {
            //this may not be included in the current build
            Class.forName("com.eternitywall.ots.OpenTimestamps");

            final NotarizationProvider nProvider = new OpenTimestampsNotarizationProvider();
            ProofMode.addNotarizationProvider(this, nProvider);
        }
        catch (ClassNotFoundException e)
        {
            //class not available
        }

    }

    @Override
    public IBinder onBind(Intent intent) {
        // TODO: Return the communication channel to the service.
        throw new UnsupportedOperationException("Not yet implemented");
    }
}