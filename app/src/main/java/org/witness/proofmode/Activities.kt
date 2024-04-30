package org.witness.proofmode

import android.content.Context
import android.net.Uri
import android.os.Parcel
import android.os.Parcelable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Delete
import androidx.room.DeleteTable
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import androidx.room.Update
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
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

object ActivityConstants
{
    const val INTENT_ACTIVITY_ITEMS_SHARED: String = "org.witness.proofmode.ITEMS_SHARED"

    const val EXTRA_FILE_NAME = "fileName"
    const val EXTRA_SHARE_TEXT = "shareText"
}

@Serializable
data class ProofableItem(val id: String, @Serializable(with = UriSerializer::class) val uri: Uri)
    : Parcelable {
    constructor(parcel: Parcel) : this(
        id = parcel.readString() ?: "",
        uri = checkNotNull(parcel.readParcelable(Uri::class.java.classLoader))
    ) {
    }

    override fun writeToParcel(parcel: Parcel, flags: Int) {
        parcel.writeString(id)
        parcel.writeParcelable(uri, flags)
    }

    override fun describeContents(): Int {
        return 0
    }

    companion object CREATOR : Parcelable.Creator<ProofableItem> {
        override fun createFromParcel(parcel: Parcel): ProofableItem {
            return ProofableItem(parcel)
        }

        override fun newArray(size: Int): Array<ProofableItem?> {
            return arrayOfNulls(size)
        }
    }
}

object SnapshotStateListOfCameraItemsSerializer :
    KSerializer<SnapshotStateList<ProofableItem>> by SnapshotStateListSerializer(ProofableItem.serializer())

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
class Activity(@PrimaryKey val id: String, @ColumnInfo(name = "data") val type: ActivityType, @ColumnInfo(name = "startTime") val startTime: Date) {
}

@Serializable
sealed class ActivityType {
    @SerialName("capture")
    @Serializable
    class MediaCaptured(
        @Serializable(with = SnapshotStateListOfCameraItemsSerializer::class) var items: SnapshotStateList<ProofableItem>) : ActivityType()

    @SerialName("imported")
    @Serializable
    class MediaImported(
        @Serializable(with = SnapshotStateListOfCameraItemsSerializer::class) var items: SnapshotStateList<ProofableItem>) : ActivityType()

    @SerialName("mediaShare")
    @Serializable
    class MediaShared(
        @Serializable(with = SnapshotStateListOfCameraItemsSerializer::class) var items: SnapshotStateList<ProofableItem>,
        val fileName: String? = null, val shareText: String? = null) : ActivityType()

    @SerialName("publicKeyShare")
    @Serializable
    class PublicKeyShared(val key: String) : ActivityType()
}

@Dao
interface ActivitiesDao {
    @Query("SELECT * FROM activities ORDER BY startTime ASC")
    suspend fun getAll(): List<Activity>

    @Update
    suspend fun update(activity: Activity?)

    @Insert
    suspend fun insert(activity: Activity?)

    @Delete
    suspend fun delete(activity: Activity?)

    @Query("DELETE FROM activities WHERE data LIKE '%\"' || :id || '\"%'")
    suspend fun deleteId(id: String?)

    @Query("SELECT * FROM activities WHERE data LIKE '%\"' || :id || '\"%' LIMIT 1")
    suspend fun activityFromProofableItemId(id: String): Activity?
}

class Converters {
    @TypeConverter
    fun fromActivityType(value: ActivityType): String {
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

object Activities: ViewModel()
{
    var activities: SnapshotStateList<Activity> = SnapshotStateList()

    private lateinit var db: AppDatabase

    init {
    }

    fun getDB(context: Context): AppDatabase {
        if (!this::db.isInitialized) {
            this.db = Room.databaseBuilder(
                context.applicationContext,
                AppDatabase::class.java, "activities-db"
            ).build()
        }
        return this.db
    }

    fun load(context: Context) {
        val db = getDB(context)
        viewModelScope.launch {
            //db.clearAllTables()
            val allFromDb = db.activitiesDao().getAll()
            MainScope().launch {
                activities.addAll(allFromDb)
            }
        }
    }

    var timeBatchWindow = 60000 * 5 //5 minutes
    private var listItems : List<ProofableItem> = ArrayList<ProofableItem>();

    fun addActivity(activity: Activity, context: Context) {
        val db = getDB(context)

        val lastActivity = this.activities.lastOrNull()
        if (activity.type is ActivityType.MediaCaptured && lastActivity != null && lastActivity.type is ActivityType.MediaCaptured && (lastActivity.startTime.time + timeBatchWindow) >= activity.startTime.time) {
            // If within the same minute, add it to the same "batch" as the previous one.

            for (pItem in activity.type.items)
            {
                if (!lastActivity.type.items.any{ it.uri == pItem.uri})
                {
                    lastActivity.type.items.add(pItem)
                }
            }

           // lastActivity.type.items += activity.type.items
            viewModelScope.launch {
                if (db.activitiesDao().activityFromProofableItemId(activity.id) == null)
                    db.activitiesDao().update(lastActivity)
            }
        } else {
            this.activities.add(activity)
            viewModelScope.launch {
                if (db.activitiesDao().activityFromProofableItemId(activity.id) == null)
                    db.activitiesDao().insert(activity)
            }
        }



    }

    fun clearActivity (id: String, context: Context) {
        val db = getDB(context)
        viewModelScope.launch {

           var activity = db.activitiesDao().activityFromProofableItemId(id)

            activities.remove(activity)

            activity?.let {
                db.activitiesDao().delete(activity)
                db.activitiesDao().deleteId(activity.id)
            }

            activities.clear()
            activities.addAll(db.activitiesDao().getAll())
        }


    }

    fun getRelatedProofableItems (context: Context, selectId: String): List<ProofableItem> {

        var listItems = ArrayList<ProofableItem>()

        viewModelScope.launch {
            var activity =
                Activities.getDB(context).activitiesDao().activityFromProofableItemId(selectId)
            if (activity != null) {
                var proofItems = Activities.getActivityProofableItems(activity)
                for (proofItem in proofItems)
                    if (!listItems.contains(proofItem))
                        listItems.add(proofItem)
            }

        }

        return listItems
    }
    fun getActivityProofableItems(activity: Activity): SnapshotStateList<ProofableItem> {
        when (activity.type) {
            is ActivityType.MediaCaptured -> return activity.type.items
            is ActivityType.MediaImported -> return activity.type.items
            is ActivityType.MediaShared -> return activity.type.items
            else -> {}
        }
        return mutableStateListOf<ProofableItem>()
    }

    fun getProofableItem(context: Context, selectId: String): List<ProofableItem> {

        var listItems = ArrayList<ProofableItem>()

        viewModelScope.launch {

                var item = db.activitiesDao().activityFromProofableItemId(selectId)
                if (item != null) {
                    var pItem = ProofableItem(item.id, Uri.parse(selectId))
                    if (!listItems.contains(pItem))
                        listItems.add(pItem)
                }

        }

        return listItems;
    }

    /**
    fun getAllCapturedAndImportedItems(context: Context): List<ProofableItem> {

        if (listItems.isEmpty())
            refreshListItems(context)

        return listItems;
    }

    fun refreshListItems (context: Context) {
        listItems = activities.flatMap { getActivityProofableItems( it ) }.toMutableStateList().withDeletedItemsRemoved(context).distinctBy { it.uri }
    }**/

    fun selectedItems(context: Context, selection: List<String>): List<ProofableItem> {
        // TODO - We don't really care about the ids here, so we match on the uri and just select
        // the first id one, if more than one mapping from id -> uri.
       // return getAllCapturedAndImportedItems(context).filter { selection.contains(it.uri.toString()) }
        var listItems = ArrayList<ProofableItem>()

        viewModelScope.launch {
            for (selectId in selection) {
                var item = db.activitiesDao().activityFromProofableItemId(selectId)
                if (item != null) {
                    var pItem = ProofableItem(item.id, Uri.parse(selectId))
                    if (!listItems.contains(pItem))
                        listItems.add(pItem)
                }
            }
        }

        return listItems
    }

    fun dateForItem(item: ProofableItem, context: Context, onDate: (Date) -> Unit) {
        // Try to find the activity that contains this item and use the "startDate" of that.
        // TODO - Fetch and/or store the correct modify date somewhere.
        val db = getDB(context)
        viewModelScope.launch {
            val activity = db.activitiesDao().activityFromProofableItemId(item.id)
            if (activity != null) {
                onDate(activity.startTime)
            }
        }
    }
}

@Composable
fun SnapshotStateList<ProofableItem>.withDeletedItemsRemoved(): SnapshotStateList<ProofableItem> {
    return this.filter { !it.isDeleted(LocalContext.current)}.toMutableStateList()
}

fun SnapshotStateList<ProofableItem>.withDeletedItemsRemoved(context: Context): SnapshotStateList<ProofableItem> {
    return this.filter { !it.isDeleted(context)}.toMutableStateList()
}

fun ProofableItem.isDeleted(context: Context): Boolean {
    try {
        val inputStream: InputStream? = context.contentResolver.openInputStream(this.uri)
        if (inputStream != null) {
            inputStream.close()
            return false
        }
    } catch (_: Exception) {
    }
    return true
}
