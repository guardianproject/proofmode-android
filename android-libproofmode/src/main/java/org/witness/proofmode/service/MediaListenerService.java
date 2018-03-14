package org.witness.proofmode.service;

import android.app.Service;
import android.content.Intent;
import android.net.Uri;
import android.os.Environment;
import android.os.FileObserver;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

import org.witness.proofmode.util.RecursiveFileObserver;

import java.io.File;

import timber.log.Timber;

public class MediaListenerService extends Service {

    public static FileObserver observer;

    public MediaListenerService() {
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {

        Timber.d("starting MediaListenerService for file observing");

        if (observer == null)
            startWatching();

        return Service.START_REDELIVER_INTENT;
    }

    private void startWatching() {
        final String pathToWatch = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM).getAbsolutePath();

        observer = new RecursiveFileObserver(pathToWatch, FileObserver.CLOSE_WRITE| FileObserver.MOVED_TO) { // set up a file observer to watch this directory on sd card
            @Override
            public void onEvent(int event, final String mediaPath) {
                if (mediaPath != null && (!mediaPath.equals(".probe"))) { // check that it's not equal to .probe because thats created every time camera is launched

                    new Handler(Looper.getMainLooper()).post(new Runnable() {
                        @Override
                        public void run() {

                            if (mediaPath.endsWith(".mp4"))
                                handleNewVideo(mediaPath);
                        }
                    });
                }
            }
        };
        observer.startWatching();


    }

    private void handleNewVideo (String mediaPath)
    {
        Intent intent = new Intent();
        intent.setData(Uri.fromFile(new File(mediaPath)));
        new MediaWatcher().onReceive(MediaListenerService.this,intent);
    }
}
