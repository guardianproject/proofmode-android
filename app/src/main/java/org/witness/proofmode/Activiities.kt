package org.witness.proofmode

import android.net.Uri
import androidx.compose.runtime.snapshots.SnapshotStateList
import java.util.Date

data class CameraItem(val id: String, val uri: Uri? = null) // TODO move this

class Activity(id: String, type: ActivityType, startTime: Date, endTime: Date?) {
    val id = id
    val type = type
    val startTime: Date = startTime
    val endTime: Date? = endTime
}

sealed class ActivityType {
    class MediaCaptured(val items: Array<CameraItem>) : ActivityType()
    class MediaImported(val items: Array<CameraItem>) : ActivityType()
    class MediaShared(val items: Array<CameraItem>, val fileName: String) : ActivityType()
    class PublicKeyShared(val key: String) : ActivityType()
}

object Activities
{
    var activities: SnapshotStateList<Activity> = SnapshotStateList()

    init {
        println("Singleton class invoked.")
        val demoActivities = listOf(
            Activity(id = "6", type = ActivityType.MediaCaptured(items = arrayOf(
                CameraItem(id = "7"),
                CameraItem(id = "8"),
                CameraItem(id = "9"),
                CameraItem(id = "10"),
                CameraItem(id = "11"),
                CameraItem(id = "12"),
                CameraItem(id = "13"),
                CameraItem(id = "14"),
            )
            ), Date(), null),
            Activity(id = "1", type = ActivityType.MediaCaptured(items = arrayOf(
                CameraItem(id = "1"),
                CameraItem(id = "2")
            )
            ), Date(), null),
            Activity(id = "5", type = ActivityType.MediaCaptured(items = arrayOf(
                CameraItem(id = "4"),
                CameraItem(id = "5"),
                CameraItem(id = "6")
            )
            ), Date(), null),
            Activity(id = "2", type = ActivityType.MediaImported(items = emptyArray()), Date(Date().time - 1000), null),
            Activity(id = "3", type = ActivityType.MediaShared(items = emptyArray(), fileName = "Filename.zip"), Date(Date().time - 2000), null),
            Activity(id = "1", type = ActivityType.MediaCaptured(items = arrayOf(
                CameraItem(id = "3")
            )
            ), Date(), null),)
        //this.activities.addAll(demoActivities)
    }

    fun addActivity(activity: Activity) {
        this.activities.add(activity)
    }
}