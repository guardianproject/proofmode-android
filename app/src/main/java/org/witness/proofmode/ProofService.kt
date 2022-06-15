package org.witness.proofmode

import android.app.*
import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import org.witness.proofmode.MainActivity

class ProofService : Service() {
    private fun showNotification(notifyMsg: String?) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val NOTIFICATION_CHANNEL_ID = packageName
            val channelName = getString(R.string.app_name)
            val chan = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                channelName,
                NotificationManager.IMPORTANCE_NONE
            )
            chan.lightColor = Color.BLUE
            chan.lockscreenVisibility = Notification.VISIBILITY_PRIVATE
            val manager = (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
            manager.createNotificationChannel(chan)
            val notificationIntent = Intent(this, MainActivity::class.java)
            val pendingIntent = PendingIntent.getActivity(
                this, 0,
                notificationIntent, PendingIntent.FLAG_IMMUTABLE
            )
            val notification = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
                .setOngoing(true)
                .setSmallIcon(R.drawable.ic_notify)
                .setContentTitle(getString(R.string.app_name))
                .setContentText(notifyMsg)
                .setPriority(NotificationManager.IMPORTANCE_MIN)
                .setCategory(Notification.CATEGORY_SERVICE)
                .setVisibility(NotificationCompat.VISIBILITY_SECRET)
                .setContentIntent(pendingIntent).build()
            startForeground(1337, notification)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        ProofMode.stop(this)
    }

    override fun onStartCommand(intent: Intent, flags: Int, startId: Int): Int {
        if (intent.action != null) {
            if (intent.action == ACTION_START) {
                ProofMode.init(this)
                showNotification(getString(R.string.waiting_proof_notify))
            } else if (intent.action == ACTION_UPDATE_NOTIFICATION) {
                showNotification(intent.getStringExtra(EXTRA_UPDATE_NOTIFICATION))
            }
        }
        return START_REDELIVER_INTENT
    }

    override fun onBind(intent: Intent): IBinder? {
        // TODO: Return the communication channel to the service.
        throw UnsupportedOperationException("Not yet implemented")
    }

    companion object {
        const val ACTION_START = "start"
        const val ACTION_UPDATE_NOTIFICATION = "notify"
        const val EXTRA_UPDATE_NOTIFICATION = "msg"
        const val ACTION_STOP = "stop"
    }
}