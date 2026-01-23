package org.witness.proofmode.service

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Parcelable
import android.preference.PreferenceManager
import android.provider.MediaStore
import android.text.TextUtils
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.bouncycastle.openpgp.PGPException
import org.json.JSONObject
import org.witness.proofmode.ProofMode
import org.witness.proofmode.ProofModeConstants
import org.witness.proofmode.c2pa.C2PAManager
import org.witness.proofmode.c2pa.PreferencesManager
import org.witness.proofmode.c2pa.SigningMode
import org.witness.proofmode.crypto.HashUtils
import org.witness.proofmode.crypto.pgp.PgpUtils
import org.witness.proofmode.notarization.NotarizationListener
import org.witness.proofmode.notarization.NotarizationProvider
import org.witness.proofmode.storage.CompositeStorageProvider
import org.witness.proofmode.storage.DefaultStorageProvider
import org.witness.proofmode.storage.FilebaseConfig
import org.witness.proofmode.storage.FilebaseStorageProvider
import org.witness.proofmode.storage.StorageListener
import org.witness.proofmode.storage.StorageProvider
import org.witness.proofmode.util.DeviceInfo
import org.witness.proofmode.util.GPSTracker
import timber.log.Timber
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileDescriptor
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MediaWatcher : BroadcastReceiver(), ProofModeV1Constants {
    private var mPrefs: SharedPreferences? = null
    private val mExec: ExecutorService = Executors.newFixedThreadPool(1)
    private var mContext: Context? = null
    private var mPassphrase: String? = null
    private val mProviders = ArrayList<NotarizationProvider>()

    var storageProvider: StorageProvider? = null
    private var mC2paManager: C2PAManager? = null

    private fun init(context: Context?, storageProvider: StorageProvider?) {
        if (mPrefs == null) mPrefs = PreferenceManager.getDefaultSharedPreferences(context)

        mContext = context

        if (storageProvider != null) this.storageProvider = storageProvider
        else this.storageProvider = createCompositeStorageProvider(mContext!!)

        mPassphrase = mPrefs!!.getString(
            ProofModeConstants.PREFS_KEY_PASSPHRASE,
            ProofModeConstants.PREFS_KEY_PASSPHRASE_DEFAULT
        )

        if (mC2paManager == null) mC2paManager =
            C2PAManager(mContext!!, PreferencesManager(mContext!!))
    }

    private fun createCompositeStorageProvider(context: Context): StorageProvider {
        val primaryProvider = DefaultStorageProvider(context)

        // Check if Filebase is configured and enabled
        val config = this.filebaseConfig
        if (config.enabled && config.isValid()) {
            try {
                val filebaseProvider = FilebaseStorageProvider(
                    config.accessKey,
                    config.secretKey,
                    config.bucketName,
                    config.endpoint
                )
                return CompositeStorageProvider(primaryProvider, filebaseProvider)
            } catch (e: Exception) {
                Log.e("MediaWatcher", "Failed to initialize Filebase provider", e)
            }
        }

        return primaryProvider
    }

    private val filebaseConfig: FilebaseConfig
        get() {
            val enabled =
                mPrefs!!.getBoolean(FilebaseConfig.PREF_FILEBASE_ENABLED, false)
            val accessKey: String =
                mPrefs!!.getString(FilebaseConfig.PREF_FILEBASE_ACCESS_KEY, "")!!
            val secretKey: String =
                mPrefs!!.getString(FilebaseConfig.PREF_FILEBASE_SECRET_KEY, "")!!
            val bucketName: String =
                mPrefs!!.getString(FilebaseConfig.PREF_FILEBASE_BUCKET_NAME, "")!!
            val endpoint: String =
                mPrefs!!.getString(
                    FilebaseConfig.PREF_FILEBASE_ENDPOINT,
                    "https://s3.filebase.com"
                )!!

            return FilebaseConfig(accessKey, secretKey, bucketName, endpoint, enabled)
        }

    /*
   // TODO Involes writing
    */
    private fun writeMapToCSV(
        context: Context?,
        mediaHash: String?,
        identifier: String?,
        hmProof: HashMap<String?, String?>,
        writeHeaders: Boolean
    ) {
        val sb = StringBuffer()

        if (writeHeaders) {
            for (key in hmProof.keys) {
                sb.append(key).append(",")
            }

            sb.append("\n")
        }

        for (key in hmProof.keys) {
            var value = hmProof.get(key)
            value = value!!.replace(',', ' ') //remove commas from CSV file
            sb.append(value).append(",")
        }

        storageProvider!!.saveText(mediaHash, identifier, sb.toString(), null)
    }


    override fun onReceive(context: Context?, intent: Intent) {
        init(context, null)

        mExec.submit(Runnable {
            val doProof = mPrefs!!.getBoolean(ProofMode.PREFS_DOPROOF, true)
            if (doProof) {
                var tmpUriMedia = intent.getData()
                if (tmpUriMedia == null) tmpUriMedia =
                    intent.getParcelableExtra<Parcelable?>(Intent.EXTRA_STREAM) as Uri?

               var mimeType = mContext?.contentResolver?.getType(tmpUriMedia!!)

                if (tmpUriMedia != null) {
                    CoroutineScope(Dispatchers.IO).launch {
                        processUri(tmpUriMedia, true, null, mimeType!!)
                    }
                }
            }
        })
    }

    fun addNotarizationProvider(provider: NotarizationProvider?) {
        mProviders.add(provider!!)
    }

    fun singleThreaded(): ExecutorService {
        return mExec
    }

    interface QueueMediaCallback {
        fun processUriDone(hash: String?)
    }

    /**
    fun queueMedia(
    uriMedia: Uri,
    autogen: Boolean,
    createdAt: Date?,
    callback: QueueMediaCallback
    ) {
    var looper = Looper.myLooper()
    if (looper == null) {
    looper = mContext!!.getMainLooper()
    }
    val handler = Handler(looper)
    mExec.submit(Runnable {

    CoroutineScope(Dispatchers.IO).launch {
    val hash = processUri(uriMedia, autogen, createdAt)
    val myRunnable: Runnable = object : Runnable {
    override fun run() {
    callback.processUriDone(hash)
    }
    }
    handler.post(myRunnable)
    }
    })
    }**/

    fun processUri(uriMedia: Uri, autogen: Boolean, createdAt: Date?, mimeType: String) {
        val intent = Intent()
        intent.setPackage("org.witness.proofmode")

        CoroutineScope(Dispatchers.IO).launch {

            var fileMedia = File(uriMedia.getPath())

            if (uriMedia.scheme != "file")
                if (mimeType.startsWith("image"))
                    fileMedia = File(getImagePath(mContext!!,uriMedia))
                else if (mimeType.startsWith("video"))
                    fileMedia = File(getVideoPath(mContext!!,uriMedia))

            var fileMediaOut = fileMedia
            var doEmbed = true

            if (!fileMediaOut.canWrite()) {
                doEmbed = false;
                fileMediaOut = File(mContext!!.getCacheDir(), fileMedia.getName() + ".c2pa")
            }

            mC2paManager?.signMediaFile(SigningMode.REMOTE,fileMedia, mimeType, fileMediaOut, doEmbed);

            val mediaHash = HashUtils.getSHA256FromFileContent(
                mContext!!.getContentResolver().openInputStream(uriMedia)
            )
            val resultHash = processUri(mContext!!, uriMedia, mediaHash, autogen, createdAt)

            if (resultHash != null) {
                //send generated event

                intent.action = (ProofMode.EVENT_PROOF_GENERATED)
                intent.putExtra(ProofMode.EVENT_PROOF_EXTRA_URI, uriMedia.toString())
                intent.putExtra(ProofMode.EVENT_PROOF_EXTRA_HASH, resultHash)
                mContext!!.sendBroadcast(intent)

            }
        }


    }

    fun processUri(
        fileUri: Uri,
        proofHash: String?,
        autogenerated: Boolean,
        createdAt: Date?
    ): String? {
        try {
            return processUri(mContext!!, fileUri, proofHash, autogenerated, createdAt)
        } catch (re: FileNotFoundException) {
            Timber.e(re, "FILENOTFOUND EXCEPTION processing media file")
            return null
        } catch (e: PGPException) {
            Timber.e(e, "PGPException EXCEPTION processing media file")
            return null
        } catch (e: IOException) {
            Timber.e(e, "IOException EXCEPTION processing media file")
            return null
        } catch (re: RuntimeException) {
            Timber.e(re, "RUNTIME EXCEPTION processing media file")
            return null
        } catch (err: Error) {
            Timber.e(err, "FATAL ERROR processing media file")

            return null
        }
    }

    @Throws(IOException::class, PGPException::class)
    fun processUri(
        context: Context,
        uriMedia: Uri,
        mediaHash: String?,
        autogenerated: Boolean,
        createdAt: Date?
    ): String? {
        if (mPrefs == null) mPrefs = PreferenceManager.getDefaultSharedPreferences(context)

        val showDeviceIds =
            mPrefs!!.getBoolean(ProofMode.PREF_OPTION_PHONE, ProofMode.PREF_OPTION_PHONE_DEFAULT)
        val showLocation = mPrefs!!.getBoolean(
            ProofMode.PREF_OPTION_LOCATION,
            ProofMode.PREF_OPTION_LOCATION_DEFAULT
        ) && checkPermissionForLocation()
        val autoNotarize =
            mPrefs!!.getBoolean(ProofMode.PREF_OPTION_NOTARY, ProofMode.PREF_OPTION_NOTARY_DEFAULT)
        val showMobileNetwork = mPrefs!!.getBoolean(
            ProofMode.PREF_OPTION_NETWORK,
            ProofMode.PREF_OPTION_NETWORK_DEFAULT
        )

        if (mediaHash != null) {
            try {
                if (proofExists(mediaHash)) return null
            } catch (e: FileNotFoundException) {
                //must not exist!
            }

            Timber.d("Writing proof for hash %s for path %s", mediaHash, uriMedia)

            var notes = ""

            try {
                val pInfo = context.getPackageManager().getPackageInfo(context.getPackageName(), 0)
                val version = pInfo.versionName
                notes =
                    pInfo.applicationInfo!!.name + " v" + version + " autogenerated=" + autogenerated
            } catch (e: PackageManager.NameNotFoundException) {
                e.printStackTrace()
            }

            val `is` = context.getContentResolver().openInputStream(uriMedia)
            writeProof(
                context,
                uriMedia,
                `is`,
                mediaHash,
                showDeviceIds,
                showLocation,
                showMobileNetwork,
                notes,
                createdAt
            )
            `is`!!.close()

            if (autoNotarize && isOnline(context)) {
                try {
                    for (provider in mProviders) {
                        val cr = context.getContentResolver()
                        val isNotarize = cr.openInputStream(uriMedia)
                        val mimeType = cr.getType(uriMedia)

                        provider.notarize(
                            mediaHash,
                            mimeType,
                            isNotarize,
                            object : NotarizationListener {
                                override fun notarizationSuccessful(hash: String?, result: String) {
                                    Timber.d(
                                        "Got notarization success response for %s",
                                        provider.getNotarizationFileExtension()
                                    )

                                    try {
                                        storageProvider!!.saveBytes(
                                            hash,
                                            hash + provider.getNotarizationFileExtension(),
                                            result.toByteArray(
                                                StandardCharsets.UTF_8
                                            ),
                                            object : StorageListener {
                                                override fun saveSuccessful(
                                                    hash: String?,
                                                    uri: String?
                                                ) {
                                                }

                                                override fun saveFailed(exception: Exception?) {
                                                }
                                            })
                                    } catch (e: Exception) {
                                        e.printStackTrace()
                                    }
                                }

                                override fun notarizationSuccessful(hash: String?, fileTmp: File) {
                                    Timber.d(
                                        "Got notarization success response for %s",
                                        fileTmp.getName()
                                    )
                                    val ext: String? = fileTmp.getName().split(".".toRegex())
                                        .dropLastWhile { it.isEmpty() }.toTypedArray()[1]

                                    try {
                                        storageProvider!!.saveStream(
                                            hash,
                                            hash + '.' + ext,
                                            FileInputStream(fileTmp),
                                            object : StorageListener {
                                                override fun saveSuccessful(
                                                    hash: String?,
                                                    uri: String?
                                                ) {
                                                }

                                                override fun saveFailed(exception: Exception?) {
                                                }
                                            })
                                    } catch (e: FileNotFoundException) {
                                        throw RuntimeException(e)
                                    }
                                }

                                override fun notarizationSuccessful(
                                    hash: String?,
                                    result: ByteArray?
                                ) {
                                    Timber.d(
                                        "Got notarization success response for %s, timestamp: %s",
                                        provider.getNotarizationFileExtension(),
                                        result
                                    )
                                    storageProvider!!.saveBytes(
                                        hash,
                                        hash + provider.getNotarizationFileExtension(),
                                        result,
                                        object : StorageListener {
                                            override fun saveSuccessful(
                                                hash: String?,
                                                uri: String?
                                            ) {
                                            }

                                            override fun saveFailed(exception: Exception?) {
                                            }
                                        })
                                }

                                override fun notarizationFailed(errCode: Int, message: String?) {
                                    Timber.d(
                                        "Got notarization error response for %s: %s",
                                        provider.getNotarizationFileExtension(),
                                        message
                                    )
                                }
                            })
                    }
                } catch (e: FileNotFoundException) {
                    Timber.e(e)
                }
            }


            return mediaHash
        } else {
            Timber.d("Unable to generated hash of media files, no proof generated")
        }

        return null
    }

    @Throws(PGPException::class, IOException::class)
    fun processBytes(
        context: Context,
        uriMedia: Uri,
        mediaBytes: ByteArray?,
        mimeType: String?,
        createdAt: Date?
    ): String? {
        if (mPrefs == null) mPrefs = PreferenceManager.getDefaultSharedPreferences(context)

        val showDeviceIds =
            mPrefs!!.getBoolean(ProofMode.PREF_OPTION_PHONE, ProofMode.PREF_OPTION_PHONE_DEFAULT)
        val showLocation = mPrefs!!.getBoolean(
            ProofMode.PREF_OPTION_LOCATION,
            ProofMode.PREF_OPTION_LOCATION_DEFAULT
        ) && checkPermissionForLocation()
        val autoNotarize =
            mPrefs!!.getBoolean(ProofMode.PREF_OPTION_NOTARY, ProofMode.PREF_OPTION_NOTARY_DEFAULT)
        val showMobileNetwork = mPrefs!!.getBoolean(
            ProofMode.PREF_OPTION_NETWORK,
            ProofMode.PREF_OPTION_NETWORK_DEFAULT
        )

        val mediaHash = HashUtils.getSHA256FromBytes(mediaBytes)

        if (mediaHash != null) {
            try {
                if (proofExists(mediaHash)) return mediaHash
            } catch (e: FileNotFoundException) {
                //must not exist!
            }

            Timber.d("Writing proof for hash %s for path %s", mediaHash, uriMedia)

            var notes = ""

            try {
                val pInfo = context.getPackageManager().getPackageInfo(context.getPackageName(), 0)
                val name = pInfo.packageName
                val version = pInfo.versionName

                notes = name + " v" + version + " autogenerated=" + true
            } catch (e: PackageManager.NameNotFoundException) {
                e.printStackTrace()
            }

            //write immediate proof
            writeProof(
                context,
                uriMedia,
                ByteArrayInputStream(mediaBytes),
                mediaHash,
                showDeviceIds,
                showLocation,
                showMobileNetwork,
                notes,
                null
            )

            if (autoNotarize && isOnline(context)) {
                for (provider in mProviders) {
                    provider.notarize(
                        mediaHash,
                        mimeType,
                        ByteArrayInputStream(mediaBytes),
                        object : NotarizationListener {
                            override fun notarizationSuccessful(hash: String?, result: String) {
                                Timber.d("Got notarization success response timestamp: %s", result)
                                try {
                                    storageProvider!!.saveBytes(
                                        hash,
                                        hash + provider.getNotarizationFileExtension(),
                                        result.toByteArray(
                                            StandardCharsets.UTF_8
                                        ),
                                        object : StorageListener {
                                            override fun saveSuccessful(
                                                hash: String?,
                                                uri: String?
                                            ) {
                                            }

                                            override fun saveFailed(exception: Exception?) {
                                            }
                                        })
                                } catch (e: Exception) {
                                    e.printStackTrace()
                                }
                            }

                            override fun notarizationSuccessful(hash: String?, fileTmp: File) {
                                Timber.d(
                                    "Got notarization success response for %s",
                                    fileTmp.getName()
                                )

                                val ext: String? = fileTmp.getName().split(".".toRegex())
                                    .dropLastWhile { it.isEmpty() }.toTypedArray()[1]
                                try {
                                    storageProvider!!.saveStream(
                                        hash,
                                        hash + '.' + ext,
                                        FileInputStream(fileTmp),
                                        object : StorageListener {
                                            override fun saveSuccessful(
                                                hash: String?,
                                                uri: String?
                                            ) {
                                            }

                                            override fun saveFailed(exception: Exception?) {
                                            }
                                        })
                                } catch (e: FileNotFoundException) {
                                    throw RuntimeException(e)
                                }
                            }

                            override fun notarizationSuccessful(hash: String?, result: ByteArray?) {
                                Timber.d(
                                    "Got notarization success response for %s, timestamp: %s",
                                    provider.getNotarizationFileExtension(),
                                    result
                                )
                                storageProvider!!.saveBytes(
                                    hash,
                                    hash + provider.getNotarizationFileExtension(),
                                    result,
                                    object : StorageListener {
                                        override fun saveSuccessful(hash: String?, uri: String?) {
                                        }

                                        override fun saveFailed(exception: Exception?) {
                                        }
                                    })
                            }

                            override fun notarizationFailed(errCode: Int, message: String?) {
                                Timber.d("Got notarization error response: %s", message)
                            }
                        })
                }
            }

            return mediaHash
        } else {
            Timber.d("Unable to generated hash of media files, no proof generated")
        }

        return null
    }

    @Throws(IOException::class, PGPException::class)
    fun processFileDescriptor(
        context: Context,
        uriMedia: Uri,
        fdMedia: FileDescriptor?,
        mimeType: String?
    ): String? {
        if (mPrefs == null) mPrefs = PreferenceManager.getDefaultSharedPreferences(context)

        val showDeviceIds =
            mPrefs!!.getBoolean(ProofMode.PREF_OPTION_PHONE, ProofMode.PREF_OPTION_PHONE_DEFAULT)
        val showLocation = mPrefs!!.getBoolean(
            ProofMode.PREF_OPTION_LOCATION,
            ProofMode.PREF_OPTION_LOCATION_DEFAULT
        ) && checkPermissionForLocation()
        val autoNotarize =
            mPrefs!!.getBoolean(ProofMode.PREF_OPTION_NOTARY, ProofMode.PREF_OPTION_NOTARY_DEFAULT)
        val showMobileNetwork = mPrefs!!.getBoolean(
            ProofMode.PREF_OPTION_NETWORK,
            ProofMode.PREF_OPTION_NETWORK_DEFAULT
        )

        var isMedia: InputStream = FileInputStream(fdMedia)
        val mediaHash = HashUtils.getSHA256FromFileContent(isMedia)
        isMedia.close()

        if (mediaHash != null) {
            try {
                if (proofExists(mediaHash)) return mediaHash
            } catch (e: FileNotFoundException) {
                //must not exist!
            }

            Timber.d("Writing proof for hash %s for path %s", mediaHash, uriMedia)

            var notes = ""

            try {
                val pInfo = context.getPackageManager().getPackageInfo(context.getPackageName(), 0)
                val version: String = pInfo.versionName!!
                notes = "ProofMode v" + version + " autogenerated=" + true
            } catch (e: PackageManager.NameNotFoundException) {
                e.printStackTrace()
            }

            //write immediate proof
            isMedia = FileInputStream(fdMedia)
            writeProof(
                context,
                uriMedia,
                isMedia,
                mediaHash,
                showDeviceIds,
                showLocation,
                showMobileNetwork,
                notes,
                null
            )
            isMedia.close()

            if (autoNotarize && isOnline(context)) {
                for (provider in mProviders) {
                    val isMediaNotarize: InputStream = FileInputStream(fdMedia)
                    provider.notarize(
                        mediaHash,
                        mimeType,
                        isMediaNotarize,
                        object : NotarizationListener {
                            override fun notarizationSuccessful(hash: String?, result: String) {
                                Timber.d("Got notarization success response timestamp: %s", result)
                                try {
                                    storageProvider!!.saveBytes(
                                        hash,
                                        hash + provider.getNotarizationFileExtension(),
                                        result.toByteArray(
                                            StandardCharsets.UTF_8
                                        ),
                                        object : StorageListener {
                                            override fun saveSuccessful(
                                                hash: String?,
                                                uri: String?
                                            ) {
                                            }

                                            override fun saveFailed(exception: Exception?) {
                                            }
                                        })
                                } catch (e: Exception) {
                                    e.printStackTrace()
                                }
                            }

                            override fun notarizationSuccessful(hash: String?, fileTmp: File) {
                                Timber.d(
                                    "Got notarization success response for %s",
                                    fileTmp.getName()
                                )

                                val ext: String? = fileTmp.getName().split(".".toRegex())
                                    .dropLastWhile { it.isEmpty() }.toTypedArray()[1]
                                try {
                                    storageProvider!!.saveStream(
                                        hash,
                                        hash + '.' + ext,
                                        FileInputStream(fileTmp),
                                        object : StorageListener {
                                            override fun saveSuccessful(
                                                hash: String?,
                                                uri: String?
                                            ) {
                                            }

                                            override fun saveFailed(exception: Exception?) {
                                            }
                                        })
                                } catch (e: FileNotFoundException) {
                                    throw RuntimeException(e)
                                }
                            }

                            override fun notarizationSuccessful(hash: String?, result: ByteArray?) {
                                Timber.d(
                                    "Got notarization success response for %s, timestamp: %s",
                                    provider.getNotarizationFileExtension(),
                                    result
                                )
                                storageProvider!!.saveBytes(
                                    hash,
                                    hash + provider.getNotarizationFileExtension(),
                                    result,
                                    object : StorageListener {
                                        override fun saveSuccessful(hash: String?, uri: String?) {
                                        }

                                        override fun saveFailed(exception: Exception?) {
                                        }
                                    })
                            }

                            override fun notarizationFailed(errCode: Int, message: String?) {
                                Timber.d("Got notarization error response: %s", message)
                            }
                        })
                }
            }

            return mediaHash
        } else {
            Timber.d("Unable to generated hash of media files, no proof generated")
        }

        return null
    }

    @Throws(FileNotFoundException::class)
    fun generateHash(uri: Uri): String? {
        return HashUtils.getSHA256FromFileContent(
            mContext!!.getContentResolver().openInputStream(uri)
        )
    }

    @Throws(FileNotFoundException::class)
    fun proofExists(hash: String?): Boolean {
        if (hash != null) return storageProvider!!.proofExists(hash)
        else return false
    }

    fun isOnline(context: Context): Boolean {
        val cm =
            context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val netInfo = cm.getActiveNetworkInfo()
        return netInfo != null && netInfo.isConnected()
    }

    @Throws(PGPException::class, IOException::class)
    private fun writeProof(
        context: Context,
        uriMedia: Uri,
        `is`: InputStream?,
        mediaHash: String?,
        showDeviceIds: Boolean,
        showLocation: Boolean,
        showMobileNetwork: Boolean,
        notes: String?,
        createdAt: Date?
    ) {
        val usePgpArmor = true

        //        File fileMediaProof = new File(fileFolder, mediaHash + PROOF_FILE_TAG);
        val proofExists = storageProvider!!.proofExists(mediaHash)

        //add data to proof csv and sign again
        val writeHeaders = !proofExists

        val hmProof = buildProof(
            context,
            uriMedia,
            mediaHash,
            showDeviceIds,
            showLocation,
            showMobileNetwork,
            notes,
            createdAt
        )

        writeMapToCSV(
            context,
            mediaHash,
            mediaHash + ProofMode.PROOF_FILE_TAG,
            hmProof,
            writeHeaders
        )

        val jProof = JSONObject(hmProof)
        storageProvider!!.saveText(
            mediaHash,
            mediaHash + ProofMode.PROOF_FILE_JSON_TAG,
            jProof.toString(),
            null
        )

        val pu = PgpUtils.getInstance()

        //sign the proof csv file
        val isProof =
            storageProvider!!.getInputStream(mediaHash, mediaHash + ProofMode.PROOF_FILE_TAG)
        //        OutputStream osProofSig = mStorageProvider.getOutputStream(mediaHash, mediaHash + PROOF_FILE_TAG + OPENPGP_FILE_TAG);
        val osProofSig = ByteArrayOutputStream()
        pu.createDetachedSignature(isProof, osProofSig, mPassphrase, usePgpArmor)
        storageProvider!!.saveBytes(
            mediaHash,
            mediaHash + ProofMode.PROOF_FILE_TAG + ProofMode.OPENPGP_FILE_TAG,
            osProofSig.toByteArray(),
            null
        )

        //sign the proof json file
        val isProofJson =
            storageProvider!!.getInputStream(mediaHash, mediaHash + ProofMode.PROOF_FILE_JSON_TAG)
        //OutputStream osProofJsonSig = mStorageProvider.getOutputStream(mediaHash, mediaHash + PROOF_FILE_JSON_TAG + OPENPGP_FILE_TAG);
        val osProofJsonSig = ByteArrayOutputStream()
        pu.createDetachedSignature(isProofJson, osProofJsonSig, mPassphrase, usePgpArmor)
        storageProvider!!.saveBytes(
            mediaHash,
            mediaHash + ProofMode.PROOF_FILE_JSON_TAG + ProofMode.OPENPGP_FILE_TAG,
            osProofJsonSig.toByteArray(),
            null
        )

        //sign the media file
        //OutputStream osMediaSig = mStorageProvider.getOutputStream(mediaHash, mediaHash + OPENPGP_FILE_TAG);
        val osMediaSig = ByteArrayOutputStream()
        pu.createDetachedSignature(`is`, osMediaSig, mPassphrase, usePgpArmor)
        storageProvider!!.saveBytes(
            mediaHash,
            mediaHash + ProofMode.OPENPGP_FILE_TAG,
            osMediaSig.toByteArray(),
            null
        )

        Timber.d("Proof written/updated for uri %s and hash %s", uriMedia, mediaHash)
    }

    val isExternalStorageWritable: Boolean
        /* Checks if external storage is available for read and write */
        get() {
            val state = Environment.getExternalStorageState()
            if (Environment.MEDIA_MOUNTED == state) {
                return true
            }
            return false
        }

    private fun buildProof(
        context: Context,
        uriMedia: Uri,
        mediaHash: String?,
        showDeviceIds: Boolean,
        showLocation: Boolean,
        showMobileNetwork: Boolean,
        notes: String?,
        createdAt: Date?
    ): HashMap<String?, String?> {

        val mediaPath: String? = getImagePath(context, uriMedia)

        val tz = TimeZone.getDefault()
        val df: DateFormat = SimpleDateFormat(
            ProofModeV1Constants.ISO_DATE_TIME_FORMAT,
            Locale.US
        ) // Quoted "Z" to indicate UTC, no timezone offset
        df.setTimeZone(tz)

        val hmProof = HashMap<String?, String?>()

        if (mediaPath != null) hmProof.put(ProofModeV1Constants.FILE_PATH, mediaPath)
        else hmProof.put(ProofModeV1Constants.FILE_PATH, uriMedia.toString())

        hmProof.put(ProofModeV1Constants.FILE_HASH_SHA_256, mediaHash)

        if (createdAt != null) hmProof.put(ProofModeV1Constants.FILE_CREATED, df.format(createdAt))
        else if (mediaPath != null) {
            val fileMedia = File(mediaPath)
            if (fileMedia.exists()) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    var attr: BasicFileAttributes? = null
                    try {
                        attr = Files.readAttributes<BasicFileAttributes?>(
                            fileMedia.toPath(),
                            BasicFileAttributes::class.java
                        )
                        val createdAtMs = attr.creationTime().toMillis()
                        hmProof.put(ProofModeV1Constants.FILE_CREATED, df.format(Date(createdAtMs)))
                    } catch (e: IOException) {
                        e.printStackTrace()
                    }
                }
            }
        }

        if (mediaPath != null) hmProof.put(
            ProofModeV1Constants.FILE_MODIFIED,
            df.format(Date(File(mediaPath).lastModified()))
        )

        hmProof.put(ProofModeV1Constants.PROOF_GENERATED, df.format(Date()))

        hmProof.put(
            ProofModeV1Constants.LANGUAGE,
            DeviceInfo.getDeviceInfo(context, DeviceInfo.Device.DEVICE_LANGUAGE)
        )
        hmProof.put(
            ProofModeV1Constants.LOCALE,
            DeviceInfo.getDeviceInfo(context, DeviceInfo.Device.DEVICE_LOCALE)
        )

        if (showDeviceIds) {
            try {
                hmProof.put(ProofModeV1Constants.DEVICE_ID, DeviceInfo.getDeviceId(context))
                hmProof.put(ProofModeV1Constants.WIFI_MAC, DeviceInfo.getWifiMacAddr())
                hmProof.put(
                    ProofModeV1Constants.I_PV_4,
                    DeviceInfo.getDeviceInfo(context, DeviceInfo.Device.DEVICE_IP_ADDRESS_IPV4)
                )
                hmProof.put(
                    ProofModeV1Constants.I_PV_6,
                    DeviceInfo.getDeviceInfo(context, DeviceInfo.Device.DEVICE_IP_ADDRESS_IPV6)
                )
                hmProof.put(
                    ProofModeV1Constants.NETWORK,
                    DeviceInfo.getDeviceInfo(context, DeviceInfo.Device.DEVICE_NETWORK)
                )
                hmProof.put(ProofModeV1Constants.DATA_TYPE, DeviceInfo.getDataType(context))
                hmProof.put(ProofModeV1Constants.NETWORK_TYPE, DeviceInfo.getNetworkType(context))
            } catch (se: SecurityException) {
            }
        }

        hmProof.put(
            ProofModeV1Constants.HARDWARE,
            DeviceInfo.getDeviceInfo(context, DeviceInfo.Device.DEVICE_HARDWARE_MODEL)
        )
        hmProof.put(
            ProofModeV1Constants.MANUFACTURER,
            DeviceInfo.getDeviceInfo(context, DeviceInfo.Device.DEVICE_MANUFACTURE)
        )
        hmProof.put(ProofModeV1Constants.SCREEN_SIZE, DeviceInfo.getDeviceInch(context))

        if (showLocation) {
            val gpsTracker = GPSTracker(context)

            if (gpsTracker.canGetLocation()) {
                var loc = gpsTracker.getLocation()

                var waitIdx = 0
                while (loc == null && waitIdx < 3) {
                    waitIdx++
                    try {
                        Thread.sleep(500)
                    } catch (e: Exception) {
                    }
                    loc = gpsTracker.getLocation()
                }

                if (loc != null) {
                    hmProof.put(
                        ProofModeV1Constants.LOCATION_LATITUDE,
                        loc.getLatitude().toString() + ""
                    )
                    hmProof.put(
                        ProofModeV1Constants.LOCATION_LONGITUDE,
                        loc.getLongitude().toString() + ""
                    )
                    hmProof.put(ProofModeV1Constants.LOCATION_PROVIDER, loc.getProvider())
                    hmProof.put(
                        ProofModeV1Constants.LOCATION_ACCURACY,
                        loc.getAccuracy().toString() + ""
                    )
                    hmProof.put(
                        ProofModeV1Constants.LOCATION_ALTITUDE,
                        loc.getAltitude().toString() + ""
                    )
                    hmProof.put(
                        ProofModeV1Constants.LOCATION_BEARING,
                        loc.getBearing().toString() + ""
                    )
                    hmProof.put(ProofModeV1Constants.LOCATION_SPEED, loc.getSpeed().toString() + "")
                    hmProof.put(ProofModeV1Constants.LOCATION_TIME, loc.getTime().toString() + "")
                } else {
                    hmProof.put(ProofModeV1Constants.LOCATION_LATITUDE, "")
                    hmProof.put(ProofModeV1Constants.LOCATION_LONGITUDE, "")
                    hmProof.put(ProofModeV1Constants.LOCATION_PROVIDER, "none")
                    hmProof.put(ProofModeV1Constants.LOCATION_ACCURACY, "")
                    hmProof.put(ProofModeV1Constants.LOCATION_ALTITUDE, "")
                    hmProof.put(ProofModeV1Constants.LOCATION_BEARING, "")
                    hmProof.put(ProofModeV1Constants.LOCATION_SPEED, "")
                    hmProof.put(ProofModeV1Constants.LOCATION_TIME, "")
                }
            }

            if (showMobileNetwork) hmProof.put(
                ProofModeV1Constants.CELL_INFO,
                DeviceInfo.getCellInfo(context)
            )
            else hmProof.put(ProofModeV1Constants.CELL_INFO, "none")
        } else {
            hmProof.put(ProofModeV1Constants.LOCATION_LATITUDE, "")
            hmProof.put(ProofModeV1Constants.LOCATION_LONGITUDE, "")
            hmProof.put(ProofModeV1Constants.LOCATION_PROVIDER, "none")
            hmProof.put(ProofModeV1Constants.LOCATION_ACCURACY, "")
            hmProof.put(ProofModeV1Constants.LOCATION_ALTITUDE, "")
            hmProof.put(ProofModeV1Constants.LOCATION_BEARING, "")
            hmProof.put(ProofModeV1Constants.LOCATION_SPEED, "")
            hmProof.put(ProofModeV1Constants.LOCATION_TIME, "")
        }

        hmProof.put(ProofModeV1Constants.SAFETY_CHECK, "false")
        hmProof.put(ProofModeV1Constants.SAFETY_CHECK_BASIC_INTEGRITY, "")
        hmProof.put(ProofModeV1Constants.SAFETY_CHECK_CTS_MATCH, "")
        hmProof.put(ProofModeV1Constants.SAFETY_CHECK_TIMESTAMP, "")

        if (!TextUtils.isEmpty(notes)) hmProof.put(ProofModeV1Constants.NOTES, notes)
        else hmProof.put(ProofModeV1Constants.NOTES, "")

        return hmProof
    }

    //  public static FileObserver observerMedia;
    fun checkPermissionForReadExternalStorage(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val result = mContext!!.checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE)
            return result == PackageManager.PERMISSION_GRANTED
        }
        return false
    }

    fun checkPermissionForLocation(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val result = mContext!!.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
            return result == PackageManager.PERMISSION_GRANTED
        }
        return true
    }

    fun stop() {
        /**
         * if (observerMedia != null)
         * {
         * observerMedia.stopWatching();
         * } */
    }

    companion object {
        const val UTF_8: String = "UTF-8"
        const val PROOF_GENERATION_DELAY_TIME_MS: Int = 500 // 30 seconds

        private var mInstance: MediaWatcher? = null

        @JvmStatic
        @Synchronized
        fun getInstance(context: Context?): MediaWatcher? {
            if (mInstance == null) {
                mInstance = MediaWatcher()
                mInstance!!.init(context, null)
            }

            return mInstance
        }


        private fun getSHA256FromFileContent(filename: String?): String? {
            try {
                val digest = MessageDigest.getInstance("SHA-256")
                val buffer = ByteArray(65536) //created at start.
                val fis: InputStream = FileInputStream(filename)
                var n = 0
                while (n != -1) {
                    n = fis.read(buffer)
                    if (n > 0) {
                        digest.update(buffer, 0, n)
                    }
                }
                val digestResult = digest.digest()
                return asHex(digestResult)
            } catch (e: Exception) {
                return null
            }
        }

        private fun getSHA256FromFileContent(fis: InputStream): String? {
            try {
                val digest = MessageDigest.getInstance("SHA-256")
                val buffer = ByteArray(65536) //created at start.
                var n = 0
                while (n != -1) {
                    n = fis.read(buffer)
                    if (n > 0) {
                        digest.update(buffer, 0, n)
                    }
                }
                val digestResult = digest.digest()
                return asHex(digestResult)
            } catch (e: Exception) {
                return null
            }
        }

        private fun asHex(arrayBytes: ByteArray): String {
            val stringBuffer = StringBuffer()
            for (i in arrayBytes.indices) {
                stringBuffer.append(
                    ((arrayBytes[i].toInt() and 0xff) + 0x100).toString(16).substring(1)
                )
            }
            return stringBuffer.toString()
        }

        @JvmStatic
        fun getImagePath(context: Context, uriMedia: Uri?): String? {
            var mediaPath: String? = null

            if (uriMedia != null) {
                if (uriMedia.getScheme() == null || uriMedia.getScheme()
                        .equals("file", ignoreCase = true)
                ) {
                    mediaPath = uriMedia.getPath()
                } else {
                    try {
                        val projection = arrayOf<String?>(MediaStore.Images.Media.DATA)

                        val cursor = context.getContentResolver()
                            .query(uriMedia, projection, null, null, null)

                        if (cursor != null) {
                            if (cursor.getCount() > 0) {
                                cursor.moveToFirst()
                                val colIdx = cursor.getColumnIndex(projection[0])
                                if (colIdx > -1) mediaPath = cursor.getString(colIdx)
                            }

                            cursor.close()
                        } else {
                            mediaPath = uriMedia.toString()
                        }
                    } catch (e: Exception) {
                        mediaPath = uriMedia.toString()
                    }
                }
            }

            return mediaPath
        }

        @JvmStatic
        fun getVideoPath(context: Context, uriMedia: Uri?): String? {
            var mediaPath: String? = null

            if (uriMedia != null) {
                if (uriMedia.getScheme() == null || uriMedia.getScheme()
                        .equals("file", ignoreCase = true)
                ) {
                    mediaPath = uriMedia.getPath()
                } else {
                    try {
                        val projection = arrayOf<String?>(MediaStore.Video.Media.DATA)

                        val cursor = context.getContentResolver()
                            .query(uriMedia, projection, null, null, null)

                        if (cursor != null) {
                            if (cursor.getCount() > 0) {
                                cursor.moveToFirst()
                                val colIdx = cursor.getColumnIndex(projection[0])
                                if (colIdx > -1) mediaPath = cursor.getString(colIdx)
                            }

                            cursor.close()
                        } else {
                            mediaPath = uriMedia.toString()
                        }
                    } catch (e: Exception) {
                        mediaPath = uriMedia.toString()
                    }
                }
            }

            return mediaPath
        }
    }
}
