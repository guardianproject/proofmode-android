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
import android.net.Uri;
import android.provider.MediaStore;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Timer;
import java.util.TimerTask;

import timber.log.Timber;

/**
 * Job to monitor when there is a change to audio in the media provider.
 */

@TargetApi(24)
public class AudioContentJob extends JobService {

    public static int AUDIO_CONTENT_JOB = 10005;

    JobParameters mRunningParams;


    // Check whether this job is currently scheduled.
    public static boolean isScheduled(Context context) {
        JobScheduler js = context.getSystemService(JobScheduler.class);
        List<JobInfo> jobs = js.getAllPendingJobs();
        if (jobs == null) {
            return false;
        }
        for (int i=0; i<jobs.size(); i++) {
            if (jobs.get(i).getId() == AUDIO_CONTENT_JOB) {
                return true;
            }
        }
        return false;
    }

    // Cancel this job, if currently scheduled.
    public static void cancelJob(Context context) {
        JobScheduler js = context.getSystemService(JobScheduler.class);
        js.cancel(AUDIO_CONTENT_JOB);
    }

    @Override
    public boolean onStartJob(JobParameters params) {
        Timber.d("Audio JOB STARTED!");
        mRunningParams = params;

        doWork ();
        jobFinished(mRunningParams, false);
        scheduleJob(AudioContentJob.this);


        cancelJob(getApplicationContext());
        scheduleJob(getApplicationContext());

        return true;
    }

    private HashMap<Uri,String> mUriStack = new HashMap<>();

    private void doWork ()
    {

        if (mRunningParams.getTriggeredContentAuthorities() != null) {

            if (mRunningParams.getTriggeredContentUris() != null) {

                for (Uri uri : mRunningParams.getTriggeredContentUris()) {

                    mUriStack.put(uri,uri.toString());

                }   

                ArrayList<Uri> uris = new ArrayList<>(mUriStack.keySet());

                for (Uri uri : uris) {
                    MediaWatcher.getInstance(AudioContentJob.this).processUri(uri, true);
                    mUriStack.remove(uri);
                }

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
        JobScheduler js =
                (JobScheduler) context.getSystemService(Context.JOB_SCHEDULER_SERVICE);
        JobInfo.Builder builder = new JobInfo.Builder(
                AUDIO_CONTENT_JOB,
                new ComponentName(context, AudioContentJob.class));
        builder.addTriggerContentUri(
                new JobInfo.TriggerContentUri(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                        JobInfo.TriggerContentUri.FLAG_NOTIFY_FOR_DESCENDANTS));
        builder.addTriggerContentUri(
                new JobInfo.TriggerContentUri(MediaStore.Audio.Media.INTERNAL_CONTENT_URI,
                        JobInfo.TriggerContentUri.FLAG_NOTIFY_FOR_DESCENDANTS));


        // Get all media changes within a tenth of a second.
        builder.setTriggerContentUpdateDelay(1);
        builder.setTriggerContentMaxDelay(100);
        
        js.schedule(builder.build());
    }

}