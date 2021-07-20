package org.witness.proofmode.service;

/**
 * Created by n8fr8 on 3/3/17.
 */
import android.annotation.TargetApi;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.provider.MediaStore;
import android.util.Log;
import android.widget.Toast;

import org.witness.proofmode.library.R;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import timber.log.Timber;

/**
 * Example stub job to monitor when there is a change to photos in the media provider.
 */

@TargetApi(24)
public class VideosContentJob extends JobService {

    public static int VIDEO_JOB_ID = 10003;

    JobParameters mRunningParams;

    public static void scheduleJob(Context context) {
        JobScheduler js =
                (JobScheduler) context.getSystemService(Context.JOB_SCHEDULER_SERVICE);
        JobInfo.Builder builder = new JobInfo.Builder(
                VIDEO_JOB_ID,
                new ComponentName(context, VideosContentJob.class));
        builder.addTriggerContentUri(
                new JobInfo.TriggerContentUri(MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                        JobInfo.TriggerContentUri.FLAG_NOTIFY_FOR_DESCENDANTS));
        builder.addTriggerContentUri(
                new JobInfo.TriggerContentUri(MediaStore.Video.Media.INTERNAL_CONTENT_URI,
                        JobInfo.TriggerContentUri.FLAG_NOTIFY_FOR_DESCENDANTS));

        js.schedule(builder.build());
    }

    // Check whether this job is currently scheduled.
    public static boolean isScheduled(Context context) {
        JobScheduler js = context.getSystemService(JobScheduler.class);
        List<JobInfo> jobs = js.getAllPendingJobs();
        if (jobs == null) {
            return false;
        }
        for (int i=0; i<jobs.size(); i++) {
            if (jobs.get(i).getId() == VIDEO_JOB_ID) {
                return true;
            }
        }
        return false;
    }

    // Cancel this job, if currently scheduled.
    public static void cancelJob(Context context) {
        JobScheduler js = context.getSystemService(JobScheduler.class);
        js.cancel(VIDEO_JOB_ID);
    }

    @Override
    public boolean onStartJob(JobParameters params) {
        Log.i("VideosContentJob", "JOB STARTED!");
        mRunningParams = params;
        doWork ();
        jobFinished(mRunningParams, false);

        return true;
    }

    private void doWork ()
    {

        if (mRunningParams.getTriggeredContentAuthorities() != null) {

            if (mRunningParams.getTriggeredContentUris() != null) {

                for (Uri uri : mRunningParams.getTriggeredContentUris()) {
                    Intent intent = new Intent();
                    intent.setData(uri);
                    new MediaWatcher().onReceive(VideosContentJob.this, intent);
                }

            } else {
                // We don't have any details about URIs (because too many changed at once),
                // so just note that we need to do a full rescan.

                Timber.w("rescan is needed since many videos changed at once");
                Toast.makeText(this,"Rescan is needed!",Toast.LENGTH_SHORT).show();

            }

        }

    }

    @Override
    public boolean onStopJob(JobParameters params) {

        return false;
    }
}