package org.witness.proofmode.org.witness.proofmode.ui

import android.content.Context
import android.net.Uri
import android.os.Parcel
import android.os.Parcelable
import android.util.Log
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
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import org.witness.proofmode.MainActivity
import java.io.InputStream

object ActivityConstants
{
    const val INTENT_ACTIVITY_ITEMS_SHARED: String = "org.witness.proofmode.ITEMS_SHARED"

    const val EXTRA_FILE_NAME = "fileName"
    const val EXTRA_SHARE_TEXT = "shareText"
}

// Lifecycle of a captured item's proof. Defaults to GENERATED so that rows
// persisted before this field existed (which were only ever written *after*
// proof completed) deserialize as already-generated rather than stuck pending.
// Camera captures are explicitly constructed PENDING and advance from there.
@Serializable
enum class ProofStatus {
    @SerialName("pending") PENDING,        // captured, proof not started
    @SerialName("generating") GENERATING,  // signing/proof in progress
    @SerialName("generated") GENERATED      // proof complete
}

// Top-level (no `this` capture) so it is safe to call from the ProofableItem
// Parcelable constructor delegation. Unknown/missing names fall back to
// GENERATED to match the serialization default.
private fun proofStatusFromName(name: String?): ProofStatus =
    ProofStatus.values().firstOrNull { it.name == name } ?: ProofStatus.GENERATED

@Serializable
data class  ProofableItem(
    val id: String,
    @Serializable(with = UriSerializer::class) val uri: Uri,
    val proofStatus: ProofStatus = ProofStatus.GENERATED
)
    : Parcelable {
    constructor(parcel: Parcel) : this(
        id = parcel.readString() ?: "",
        uri = checkNotNull(parcel.readParcelable(Uri::class.java.classLoader)),
        proofStatus = proofStatusFromName(parcel.readString())
    ) {
    }

    override fun writeToParcel(parcel: Parcel, flags: Int) {
        parcel.writeString(id)
        parcel.writeParcelable(uri, flags)
        parcel.writeString(proofStatus.name)
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
        // Take a defensive copy so concurrent mutation on the main thread
        // (e.g. another photo capture appending items while Room serializes
        // on a background thread) can't trigger ConcurrentModificationException.
        ListSerializer(dataSerializer).serialize(encoder, value.toList())
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
    suspend fun update(activity: Activity)

    @Insert
    suspend fun insert(activity: Activity)

    @Delete
    suspend fun delete(activity: Activity)

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
            db = Room.databaseBuilder(
                context.applicationContext,
                AppDatabase::class.java, "activities-db"
            ).build()
        }
        return db
    }

    fun load(context: Context) {
        val db = getDB(context)
        viewModelScope.launch {
            //db.clearAllTables()
            val allFromDb = db.activitiesDao().getAll()
            MainScope().launch {
                activities.clear()
                activities.addAll(allFromDb)
                if (context is MainActivity)
                {
                    (context as MainActivity).checkNoPicsView()
                }
            }
        }
    }

    var timeBatchWindow = 60000 * 5 //5 minutes
    private var listItems : List<ProofableItem> = ArrayList<ProofableItem>();

    fun addActivity(activity: Activity, context: Context) {
        val db = getDB(context)

        // SnapshotStateList writes must happen on the main thread for Compose to
        // schedule a recomposition. addActivity is frequently invoked from a
        // background/broadcast callback (MediaWatcher -> ProofEventReceiver), so
        // marshal the in-memory mutation onto the main thread the same way
        // load() does; otherwise the Activities feed only refreshes once the
        // user taps or scrolls. The DB writes are suspend calls that Room
        // dispatches to its own executor.
        MainScope().launch {
            val lastActivity = activities.lastOrNull()
            if (activity.type is ActivityType.MediaCaptured && lastActivity != null && lastActivity.type is ActivityType.MediaCaptured && (lastActivity.startTime.time + timeBatchWindow) >= activity.startTime.time) {
                // If within the same batch window, add it to the same "batch" as the previous one.
                for (pItem in activity.type.items) {
                    if (!lastActivity.type.items.any { it.uri == pItem.uri }) {
                        lastActivity.type.items.add(pItem)
                    }
                }

                if (db.activitiesDao().activityFromProofableItemId(activity.id) == null)
                    db.activitiesDao().update(lastActivity)
            } else {
                activities.add(activity)
                if (db.activitiesDao().activityFromProofableItemId(activity.id) == null)
                    db.activitiesDao().insert(activity)
            }

            if (context is MainActivity) {
                context.checkNoPicsView()
            }
        }
    }

    // Items that carry a proof lifecycle (captured/imported/shared media).
    private fun proofItemsOf(activity: Activity): SnapshotStateList<ProofableItem>? =
        when (val t = activity.type) {
            is ActivityType.MediaCaptured -> t.items
            is ActivityType.MediaImported -> t.items
            is ActivityType.MediaShared -> t.items
            is ActivityType.PublicKeyShared -> null
        }

    // Locate a live in-memory item by its (stable) media URI. Must be called on
    // the main thread since it reads the Compose snapshot list.
    private fun findItemByUri(uriString: String): Pair<Activity, Int>? {
        for (activity in activities) {
            val items = proofItemsOf(activity) ?: continue
            val idx = items.indexOfFirst { it.uri.toString() == uriString }
            if (idx >= 0) return activity to idx
        }
        return null
    }

    // Step 1 of the capture lifecycle: show the freshly-captured media right
    // away, before any proof work, as a PENDING item. The real SHA-256 hash is
    // not known yet (C2PA embedding will change the bytes), so the URI doubles
    // as the placeholder id until markProofGenerated assigns the real hash.
    fun addCapturedPending(uriString: String, context: Context) {
        addActivity(
            Activity(
                uriString,
                ActivityType.MediaCaptured(
                    items = mutableStateListOf(
                        ProofableItem(uriString, Uri.parse(uriString), ProofStatus.PENDING)
                    )
                ),
                Date()
            ),
            context
        )
    }

    // Step 2: signing/proof generation has started for this URI. Only advances
    // an item that already exists (i.e. a camera capture); imports have no
    // pending row and are intentionally left untouched here.
    fun markProofGenerating(uriString: String, context: Context) {
        val db = getDB(context)
        MainScope().launch {
            val (activity, idx) = findItemByUri(uriString) ?: return@launch
            val items = proofItemsOf(activity) ?: return@launch
            // Don't regress a completed item if events arrive out of order.
            if (items[idx].proofStatus == ProofStatus.GENERATED) return@launch
            items[idx] = items[idx].copy(proofStatus = ProofStatus.GENERATING)
            db.activitiesDao().update(activity)
        }
    }

    // Step 3: proof is complete. Flip the existing pending item to GENERATED and
    // stamp it with the real hash. If no pending item exists (gallery import, or
    // the capture event was missed) fall back to creating a fresh GENERATED item
    // — preserving the previous behaviour for those paths.
    fun markProofGenerated(uriString: String, hash: String, imported: Boolean, context: Context) {
        val db = getDB(context)
        MainScope().launch {
            val found = findItemByUri(uriString)
            if (found != null) {
                val (activity, idx) = found
                val items = proofItemsOf(activity)
                if (items != null) {
                    items[idx] = items[idx].copy(id = hash, proofStatus = ProofStatus.GENERATED)
                    db.activitiesDao().update(activity)
                    if (context is MainActivity) context.checkNoPicsView()
                    return@launch
                }
            }

            val item = ProofableItem(hash, Uri.parse(uriString), ProofStatus.GENERATED)
            val type = if (imported)
                ActivityType.MediaImported(items = mutableStateListOf(item))
            else
                ActivityType.MediaCaptured(items = mutableStateListOf(item))
            addActivity(Activity(uriString, type, Date()), context)
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

            DeletedStatusCache.clear()
            activities.clear()
            activities.addAll(db.activitiesDao().getAll())
        }


    }

    fun getRelatedProofableItems (context: Context, selectId: String): List<ProofableItem> {

        var listItems = ArrayList<ProofableItem>()

        viewModelScope.launch {
            var activity =
                getDB(context).activitiesDao().activityFromProofableItemId(selectId)
            if (activity != null) {
                var proofItems = getActivityProofableItems(activity)
                for (proofItem in proofItems)
                    if (!listItems.contains(proofItem))
                        listItems.add(proofItem)
            }

        }

        return listItems
    }
    fun getActivityProofableItems(activity: Activity): SnapshotStateList<ProofableItem> {
        val items = when (activity.type) {
            is ActivityType.MediaCaptured -> activity.type.items
            is ActivityType.MediaImported -> activity.type.items
            is ActivityType.MediaShared -> activity.type.items
            else -> emptyList()
        }
        Log.d("ProofableItems", "$items")
        return items.distinctBy { it.uri }.toMutableStateList()

    }

    fun getProofableItem(context: Context, selectId: String): List<ProofableItem> {
        getDB(context)

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

object DeletedStatusCache {
    private val cache = mutableMapOf<String, Boolean>()

    fun get(uri: Uri): Boolean? = cache[uri.toString()]

    fun put(uri: Uri, deleted: Boolean) {
        cache[uri.toString()] = deleted
    }

    fun clear() {
        cache.clear()
    }
}

fun ProofableItem.isDeleted(context: Context): Boolean {
    DeletedStatusCache.get(this.uri)?.let { return it }

    val deleted = try {
        val inputStream: InputStream? = context.contentResolver.openInputStream(this.uri)
        if (inputStream != null) {
            inputStream.close()
            false
        } else {
            true
        }
    } catch (_: Exception) {
        true
    }

    DeletedStatusCache.put(this.uri, deleted)
    return deleted
}
