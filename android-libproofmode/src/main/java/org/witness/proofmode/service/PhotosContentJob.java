package org.witness.proofmode.service;

/**
 * Created by n8fr8 on 3/3/17.
 */

import android.annotation.TargetApi;
import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;
import android.net.NetworkRequest;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.provider.MediaStore;
import android.text.TextUtils;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Objects;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import timber.log.Timber;

/**
 * Job to monitor when there is a change to photos in the media provider.
 */

@TargetApi(24)
public class PhotosContentJob extends JobService {

    private static int PHOTOS_CONTENT_JOB = 10001;

    JobParameters mRunningParams;


    // Check whether this job is currently scheduled.
    public static boolean isScheduled(Context context) {
        JobScheduler js = context.getSystemService(JobScheduler.class);
        List<JobInfo> jobs = js.getAllPendingJobs();
        if (jobs == null) {
            return false;
        }
        for (int i=0; i<jobs.size(); i++) {
            if (jobs.get(i).getId() == PHOTOS_CONTENT_JOB) {
                return true;
            }
        }
        return false;
    }

    // Cancel this job, if currently scheduled.
    public static void cancelJob(Context context) {
        JobScheduler js = context.getSystemService(JobScheduler.class);
        js.cancel(PHOTOS_CONTENT_JOB);
    }

    @Override
    public boolean onStartJob(JobParameters params) {
        Timber.d("Photos JOB STARTED!");
        mRunningParams = params;

        doWork ();
        jobFinished(mRunningParams, false);

        // manual reschedule
        cancelJob(getApplicationContext());
        scheduleJob(getApplicationContext());

        return true;
    }

    private void doWork ()
    {


                if (mRunningParams.getTriggeredContentAuthorities() != null) {

                    if (mRunningParams.getTriggeredContentUris() != null) {

                        HashMap<String, Uri> uriList = new HashMap<String, Uri>();

                        for (Uri uri : mRunningParams.getTriggeredContentUris()) {

                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                                uri = MediaStore.setRequireOriginal(uri);
                            }

                            String mediaPath = MediaWatcher.getImagePath(PhotosContentJob.this, uri);

                            //if we can get to the direct file path, we should!
                            if (mediaPath != null) {
                                File fileNew = new File(mediaPath);
                                if (!fileNew.getName().startsWith("."))
                                    uriList.put(mediaPath, Uri.fromFile(fileNew));
                            }
                            else
                                uriList.put(uri.toString(), uri);
                        }

                        MediaWatcher mw = MediaWatcher.getInstance(PhotosContentJob.this);

                        String DEFAULT_PHOTO_TYPE = "image/jpeg";

                        for (Uri uri : uriList.values())
                            mw.processUri(uri, true, null,DEFAULT_PHOTO_TYPE);
                    } else {
                        // We don't have any details about URIs (because too many changed at once),
                        // so just note that we need to do a full rescan.

                        Timber.w("rescan is needed since many photos changed at once");
                        //      Toast.makeText(this,"Rescan is needed!",Toast.LENGTH_SHORT).show();


                    }
                }



    }




    @Override
    public boolean onStopJob(JobParameters params) {
        return false;
    }


    public static void scheduleJob(Context context) {
        PHOTOS_CONTENT_JOB++;

        JobScheduler js =
                (JobScheduler) context.getSystemService(Context.JOB_SCHEDULER_SERVICE);
        JobInfo.Builder builder = new JobInfo.Builder(
                PHOTOS_CONTENT_JOB,
                new ComponentName(context, PhotosContentJob.class));

        builder.addTriggerContentUri(
                new JobInfo.TriggerContentUri(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                        JobInfo.TriggerContentUri.FLAG_NOTIFY_FOR_DESCENDANTS));

        builder.addTriggerContentUri(
                new JobInfo.TriggerContentUri(MediaStore.Images.Media.INTERNAL_CONTENT_URI,
                        JobInfo.TriggerContentUri.FLAG_NOTIFY_FOR_DESCENDANTS));

      //  builder.addTriggerContentUri(
        //        new JobInfo.TriggerContentUri(Uri.parse("content://media/external_primary"),
          //              JobInfo.TriggerContentUri.FLAG_NOTIFY_FOR_DESCENDANTS));

        // Get all media changes within a tenth of a second.
        builder.setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY);
        builder.setTriggerContentUpdateDelay(1000);
        builder.setTriggerContentMaxDelay(1000);
        js.schedule(builder.build());
    }

}