package org.witness.proofmode

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.platform.LocalContext
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import androidx.room.Update
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import java.util.Date
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import java.io.InputStream

@Serializable
data class CameraItem(val id: String, @Serializable(with = UriSerializer::class) val uri: Uri? = null) // TODO move this

object SnapshotStateListOfCameraItemsSerializer :
    KSerializer<SnapshotStateList<CameraItem>> by SnapshotStateListSerializer(CameraItem.serializer())

class SnapshotStateListSerializer<T>(private val dataSerializer: KSerializer<T>) : KSerializer<SnapshotStateList<T>> {
    override val descriptor: SerialDescriptor = dataSerializer.descriptor
    override fun serialize(encoder: Encoder, value: SnapshotStateList<T>) {
        ListSerializer(dataSerializer).serialize(encoder, value as List<T>)
    }
    override fun deserialize(decoder: Decoder): SnapshotStateList<T> {
        val list = mutableStateListOf<T>()
        val items = ListSerializer(dataSerializer).deserialize(decoder)
        list.addAll(items)
        return list
    }
}

object UriSerializer : KSerializer<Uri> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("Uri", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: Uri) = encoder.encodeString(value.toString())
    override fun deserialize(decoder: Decoder): Uri {
        return Uri.parse(decoder.decodeString())
    }
}

@Entity(tableName = "activities")
class Activity(id: String, type: ActivityType, startTime: Date) {
    @PrimaryKey val id = id
    @ColumnInfo(name = "data") val type = type
    @ColumnInfo(name = "startTime") val startTime = startTime
}

@Serializable
sealed class ActivityType {
    @SerialName("capture")
    @Serializable
    class MediaCaptured(
        @Serializable(with = SnapshotStateListOfCameraItemsSerializer::class) var items: SnapshotStateList<CameraItem>) : ActivityType()
    class MediaImported(val items: SnapshotStateList<CameraItem>) : ActivityType()
    class MediaShared(val items: SnapshotStateList<CameraItem>, val fileName: String) : ActivityType()
    class PublicKeyShared(val key: String) : ActivityType()
}

@Dao
interface ActivitiesDao {
    @Query("SELECT * FROM activities ORDER BY startTime DESC")
    fun getAll(): List<Activity>

    @Update
    fun update(activity: Activity?)

    @Insert
    fun insert(activity: Activity?)
}

class Converters {
    @TypeConverter
    fun fromActivityType(value: ActivityType): String? {
        return Json.encodeToString(value)
    }

    @TypeConverter
    fun toActivityType(activityTypeString: String?): ActivityType? {
        if (activityTypeString != null) {
            return Json.decodeFromString(activityTypeString)
        }
        return null
    }

    @TypeConverter
    fun fromTimestamp(value: Long?): Date {
        if (value != null) {
            return Date(value)
        }
        return Date()
    }

    @TypeConverter
    fun dateToTimestamp(date: Date?): Long? {
        return date?.time
    }
}

@Database(entities = [Activity::class], version = 1)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun activitiesDao(): ActivitiesDao
}

object Activities
{
    var activities: SnapshotStateList<Activity> = SnapshotStateList()

    lateinit var db: AppDatabase

    init {
        println("Singleton class invoked.")
        val demoActivities = listOf(
            Activity(
                id = "6", type = ActivityType.MediaCaptured(
                    items = mutableStateListOf(
                        CameraItem(id = "7"),
                        CameraItem(id = "8"),
                        CameraItem(id = "9"),
                        CameraItem(id = "10"),
                        CameraItem(id = "11"),
                        CameraItem(id = "12"),
                        CameraItem(id = "13"),
                        CameraItem(id = "14"),
                    )
                ), Date()
            ),
            Activity(
                id = "1", type = ActivityType.MediaCaptured(
                    items = mutableStateListOf(
                        CameraItem(id = "1"),
                        CameraItem(id = "2")
                    )
                ), Date()
            ),
            Activity(
                id = "5", type = ActivityType.MediaCaptured(
                    items = mutableStateListOf(
                        CameraItem(id = "4"),
                        CameraItem(id = "5"),
                        CameraItem(id = "6")
                    )
                ), Date()
            ),
            Activity(
                id = "2",
                type = ActivityType.MediaImported(items = mutableStateListOf()),
                Date(Date().time - 1000)

            ),
            Activity(
                id = "3",
                type = ActivityType.MediaShared(items = mutableStateListOf(), fileName = "Filename.zip"),
                Date(Date().time - 2000)

            ),
            Activity(
                id = "1", type = ActivityType.MediaCaptured(
                    items = mutableStateListOf(
                        CameraItem(id = "3")
                    )
                ), Date()
            ),
        )
        //this.activities.addAll(demoActivities)
    }

    fun getDB(context: Context): AppDatabase {
        if (!this::db.isInitialized) {
            this.db = Room.databaseBuilder(
                context.applicationContext,
                AppDatabase::class.java, "activities-db"
            ).allowMainThreadQueries().build() // FIXME - NOT ON MAIN THREAD!
        }
        return this.db
    }

    fun load(context: Context) {
        val db = getDB(context)
        //db.clearAllTables()
        val allFromDb = db.activitiesDao().getAll()
        this.activities.addAll(allFromDb)
    }

    fun addActivity(activity: Activity, context: Context) {
        val db = getDB(context)
        val lastActivity = this.activities.lastOrNull()
        if (activity.type is ActivityType.MediaCaptured && lastActivity != null && lastActivity.type is ActivityType.MediaCaptured && (lastActivity.startTime.time + 60000) >= activity.startTime.time ) {
            // If within the same minute, add it to the same "batch" as the previous one.
            lastActivity.type.items += activity.type.items
            db.activitiesDao().update(lastActivity)
        } else {
            this.activities.add(activity)
            db.activitiesDao().insert(activity)
        }
    }
}

@Composable
fun SnapshotStateList<CameraItem>.withDeletedItemsRemoved(): SnapshotStateList<CameraItem> {
    return this.filter { !it.isDeleted(LocalContext.current)}.toMutableStateList()
}

fun CameraItem.isDeleted(context: Context): Boolean {
    if (null != this.uri) {
        try {
            val inputStream: InputStream? = context.getContentResolver().openInputStream(this.uri)
            if (inputStream != null) {
                inputStream.close()
                return false
            }
        } catch (_: Exception) {
        }
    }
    return true
}
