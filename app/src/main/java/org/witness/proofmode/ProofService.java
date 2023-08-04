package org.witness.proofmode;

import static org.witness.proofmode.ProofMode.EVENT_PROOF_GENERATED;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.os.Build;
import android.os.IBinder;
import android.widget.Toast;

import androidx.core.app.NotificationCompat;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import org.witness.proofmode.notaries.GoogleSafetyNetNotarizationProvider;
import org.witness.proofmode.notaries.OpenTimestampsNotarizationProvider;
import org.witness.proofmode.notaries.SafetyNetCheck;
import org.witness.proofmode.notarization.NotarizationProvider;

import java.util.UUID;

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
        LocalBroadcastManager.getInstance(this).unregisterReceiver(mReceiver);

    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {

        if (intent.getAction()!= null) {

            if (intent.getAction().equals(ACTION_START))
            {

                showNotification(getString(R.string.waiting_proof_notify));

                ProofMode.initBackgroundService(this);

            }
            else if (intent.getAction().equals(ACTION_UPDATE_NOTIFICATION))
            {
                showNotification(intent.getStringExtra(EXTRA_UPDATE_NOTIFICATION));
            }
        }

        startEventListeners();

        return START_REDELIVER_INTENT;
    }

    private BroadcastReceiver mReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {

            //got event

            Toast.makeText(ProofService.this,"Got proof event: " + intent.getData(), Toast.LENGTH_SHORT).show();
            /**
             Activities.INSTANCE.addActivity(new Activity(UUID.randomUUID().toString(), ActivityType.MediaCaptured(
             items = mutableStateListOf(
             ProofableItem(UUID.randomUUID().toString(), intent.getData())
             )
             ), Date()),this);**/
        }
    };

    private void startEventListeners () {
        LocalBroadcastManager.getInstance(getApplicationContext()).registerReceiver(mReceiver, new IntentFilter(EVENT_PROOF_GENERATED));
    }


    @Override
    public IBinder onBind(Intent intent) {
        // TODO: Return the communication channel to the service.
        throw new UnsupportedOperationException("Not yet implemented");
    }
}