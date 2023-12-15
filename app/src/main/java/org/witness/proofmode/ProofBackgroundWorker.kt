package org.witness.proofmode.org.witness.proofmode

import android.content.Context
import android.provider.MediaStore
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import org.witness.proofmode.service.MediaWatcher

class ProofBackgroundWorker (appContext: Context, workerParams: WorkerParameters):
    Worker(appContext, workerParams) {

    override fun doWork(): Result {

        // Do the work here--in this case, process new media items

       // val mw = MediaWatcher.getInstance(applicationContext)
        //val resultProofHash = mw.processUri(uri, true, null)

        // Indicate whether the work finished successfully with the Result
        scheduleWork(applicationContext)
        return Result.success()
    }

    companion object {
        fun scheduleWork(context: Context) {

            val photoCheckBuilder = OneTimeWorkRequest.Builder(ProofBackgroundWorker::class.java)
            photoCheckBuilder.setConstraints(
                Constraints.Builder()
                    .addContentUriTrigger(MediaStore.Images.Media.INTERNAL_CONTENT_URI, true)
                    .addContentUriTrigger(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true)
                    .build()
            )
            var tag = "proofwork"
            val photoCheckWork: OneTimeWorkRequest = photoCheckBuilder.build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                tag,
                ExistingWorkPolicy.REPLACE, photoCheckWork
            )
        }
    }
}