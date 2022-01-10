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

import androidx.annotation.RequiresApi;
import androidx.core.app.NotificationCompat;

public class ProofService extends Service {

    @Override
    public void onCreate() {
        super.onCreate();


    }

    private void showNotification () {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {

            String NOTIFICATION_CHANNEL_ID = getPackageName();
            String channelName = getString(R.string.app_name);
            NotificationChannel chan = new NotificationChannel(NOTIFICATION_CHANNEL_ID, channelName, NotificationManager.IMPORTANCE_NONE);
            chan.setLightColor(Color.BLUE);
            chan.setLockscreenVisibility(Notification.VISIBILITY_PRIVATE);
            NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            assert manager != null;
            manager.createNotificationChannel(chan);

            Intent notificationIntent = new Intent(this, MainActivity.class);

            PendingIntent pendingIntent = PendingIntent.getActivity(this, 0,
                    notificationIntent, PendingIntent.FLAG_IMMUTABLE);

            Notification notification = new NotificationCompat.Builder(this,NOTIFICATION_CHANNEL_ID)
                    .setOngoing(true)
                    .setSmallIcon(R.drawable.ic_notify)
                    .setContentTitle(getString(R.string.app_name))
                    .setContentText(getString(R.string.generating_proof_notify))
                    .setPriority(NotificationManager.IMPORTANCE_MIN)
                    .setCategory(Notification.CATEGORY_SERVICE)
                    .setVisibility(NotificationCompat.VISIBILITY_SECRET)
                    .setContentIntent(pendingIntent).build();

            startForeground(1337, notification);


        }
    }



    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            showNotification();
        }

        return super.onStartCommand(intent, flags, startId);
    }

    @Override
    public IBinder onBind(Intent intent) {
        // TODO: Return the communication channel to the service.
        throw new UnsupportedOperationException("Not yet implemented");
    }
}