package org.witness.proofmode.service;

import android.content.Context;
import android.content.Intent;
import android.support.v4.app.JobIntentService;

/**
 * Created by n8fr8 on 3/7/18.
 */
public class UpdateJobIntentService extends JobIntentService {
    static final int JOB_ID = 1000;
    static final String WORK_DOWNLOAD_ARTWORK = ".DOWNLOAD_ARTWORK";

    static void enqueueWork(Context context, Intent work) {

        enqueueWork(context, PhotosContentJob.class, JOB_ID, work);
        enqueueWork(context, VideosContentJob.class, JOB_ID, work);


    }

    @Override
    public void onCreate() {
        super.onCreate();

    }

    @Override
    protected void onHandleWork(Intent intent) {
        if (WORK_DOWNLOAD_ARTWORK.equals(intent.getAction())) {
          //  mDownloader.download(intent.getStringExtra("URL"))
        }
    }

    @Override
    public boolean onStopCurrentWork() {
        return true;//!mDownloader.isFinished();
    }
}