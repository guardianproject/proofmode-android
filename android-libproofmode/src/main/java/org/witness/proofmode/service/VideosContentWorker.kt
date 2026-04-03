package org.witness.proofmode.service

import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.work.Constraints
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import timber.log.Timber
import java.io.File
import java.time.Duration

/**
 * WorkManager replacement for VideosContentJob.
 * Monitors MediaStore for new videos and processes them via MediaWatcher.
 * Uses content URI triggers and network connectivity constraint to work
 * reliably on Android 15+.
 *
 * Uses WorkerParameters.triggeredContentUris to get the specific URIs
 * that changed, matching the original JobScheduler approach.
 */
class VideosContentWorker(appContext: Context, workerParams: WorkerParameters) :
    Worker(appContext, workerParams) {

    override fun doWork(): Result {
        Timber.d("Videos Worker STARTED!")

        val triggeredUris = triggeredContentUris
        val triggeredAuthorities = triggeredContentAuthorities

        Timber.d("Videos Worker triggered URIs: %d, authorities: %s",
            triggeredUris.size, triggeredAuthorities)

        if (triggeredAuthorities.isNotEmpty()) {
            if (triggeredUris.isNotEmpty()) {
                val uriList = HashMap<String, Uri>()

                for (uri in triggeredUris) {
                    val key = uri.toString()
                    synchronized(recentlyProcessed) {
                        if (recentlyProcessed.contains(key)) {
                            Timber.d("Videos Worker skipping duplicate URI: %s", key)
                            continue
                        }
                    }

                    var resolvedUri = uri

                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        resolvedUri = MediaStore.setRequireOriginal(resolvedUri)
                    }

                    val mediaPath = MediaWatcher.getVideoPath(applicationContext, resolvedUri)

                    if (mediaPath != null) {
                        val fileNew = File(mediaPath)
                        if (!fileNew.name.startsWith(".")) {
                            uriList[mediaPath] = Uri.fromFile(fileNew)
                        }
                    } else {
                        uriList[resolvedUri.toString()] = resolvedUri
                    }
                }

                Timber.d("Videos Worker processing %d items (%d skipped as duplicates)",
                    uriList.size, triggeredUris.size - uriList.size)

                val mw = MediaWatcher.getInstance(applicationContext)
                val defaultVideoType = "video/mp4"

                if (mw != null) {
                    for ((key, uri) in uriList) {
                        mw.ingestMedia(uri, true, null, defaultVideoType, null)
                        synchronized(recentlyProcessed) {
                            recentlyProcessed.add(key)
                            while (recentlyProcessed.size > MAX_RECENT_CACHE) {
                                recentlyProcessed.iterator().let { it.next(); it.remove() }
                            }
                        }
                    }
                }
            } else {
                Timber.w("Rescan needed since many videos changed at once")
            }
        }

        // Re-enqueue a fresh work request to listen for the next content change
        enqueueFresh(applicationContext)

        return Result.success()
    }

    companion object {
        private const val WORK_TAG = "videos_content_worker"
        private const val MAX_RECENT_CACHE = 500
        private val recentlyProcessed = LinkedHashSet<String>()

        private fun buildConstraints(): Constraints {
            return Constraints.Builder()
                .addContentUriTrigger(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, true)
                .addContentUriTrigger(MediaStore.Video.Media.INTERNAL_CONTENT_URI, true)
                .setTriggerContentUpdateDelay(Duration.ofMillis(1000))
                .setTriggerContentMaxDelay(Duration.ofMillis(1000))
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()
        }

        private fun enqueueFresh(context: Context) {
            val workRequest = OneTimeWorkRequest.Builder(VideosContentWorker::class.java)
                .setConstraints(buildConstraints())
                .addTag(WORK_TAG)
                .build()

            WorkManager.getInstance(context).enqueue(workRequest)
            Timber.d("Videos content worker enqueued (fresh)")
        }

        @JvmStatic
        fun scheduleWork(context: Context) {
            WorkManager.getInstance(context).cancelAllWorkByTag(WORK_TAG)
            enqueueFresh(context)
        }

        @JvmStatic
        fun cancelWork(context: Context) {
            WorkManager.getInstance(context).cancelAllWorkByTag(WORK_TAG)
        }
    }
}
