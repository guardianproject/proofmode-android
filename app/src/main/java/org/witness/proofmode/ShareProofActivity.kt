package org.witness.proofmode

import android.Manifest
import android.app.Dialog
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.LabeledIntent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.*
import androidx.preference.PreferenceManager
import android.provider.MediaStore
import android.text.TextUtils
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.webkit.MimeTypeMap
import android.widget.CheckBox
import android.widget.CompoundButton
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.documentfile.provider.DocumentFile
import org.bouncycastle.openpgp.PGPException
import org.witness.proofmode.PermissionActivity.Companion.hasPermissions
import org.witness.proofmode.ProofModeConstants.PREFS_KEY_PASSPHRASE
import org.witness.proofmode.ProofModeConstants.PREFS_KEY_PASSPHRASE_DEFAULT
import org.witness.proofmode.crypto.HashUtils
import org.witness.proofmode.crypto.pgp.PgpUtils
import org.witness.proofmode.databinding.ActivityShareBinding
import org.witness.proofmode.service.MediaWatcher
import timber.log.Timber
import java.io.*
import java.net.URLDecoder
import java.text.SimpleDateFormat
import java.util.*
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ShareProofActivity : AppCompatActivity() {
    private lateinit var binding: ActivityShareBinding
    private var sendMedia = true

    private val hashCache = HashMap<String, String?>()

    private lateinit var pgpUtils : PgpUtils
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityShareBinding.inflate(layoutInflater)
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        pgpUtils = PgpUtils.getInstance(this, prefs.getString(PREFS_KEY_PASSPHRASE,PREFS_KEY_PASSPHRASE_DEFAULT))
        setContentView(binding.root)
    }

    override fun onResume() {
        super.onResume()
        val toolbar = binding.toolbar
        setSupportActionBar(toolbar)
        supportActionBar?.apply {
            title = ""
            setDisplayHomeAsUpEnabled(false)
            setDisplayShowHomeEnabled(false)
        }

        val tvInfoBasic = binding.tvInfoBasic
        tvInfoBasic.setOnClickListener { showInfoBasic() }
        val tvInfoRobust = binding.tvInfoRobust
        tvInfoRobust.setOnClickListener { showInfoRobust() }
        // Get intent, action and MIME type
        val intent = intent
        val action = intent.action
        if (Intent.ACTION_SEND_MULTIPLE == action) {
            val mediaUris = intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM) ?: emptyList()

            //just check the first file
            if (mediaUris.isNotEmpty()) {
                displayProgress(getString(R.string.progress_checking_proof))
                CheckProofTasks(this).execute(mediaUris[0])
            }
        } else if (Intent.ACTION_SEND == action || action!!.endsWith("SHARE_PROOF")) {
            intent.action = Intent.ACTION_SEND
            var mediaUri = intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
            if (mediaUri == null) mediaUri = intent.data
            if (mediaUri != null) {
                mediaUri = cleanUri(mediaUri)
                displayProgress(getString(R.string.progress_checking_proof))
                CheckProofTasks(this).execute(mediaUri)
            }
        } else finish()
    }

    private fun cleanUri(mediaUri: Uri): Uri {
        //content://com.google.android.apps.photos.contentprovider/0/1/content%3A%2F%2Fmedia%2Fexternal%2Fimages%2Fmedia%2F3517/ORIGINAL/NONE/image%2Fjpeg/765892976
        var resultUri = mediaUri
        val contentEnc = "content://"
        val paths = mediaUri.pathSegments
        for (path in paths) {
            if (path.startsWith(contentEnc)) {
                try {
                    val pathDec = URLDecoder.decode(path, "UTF-8")
                    resultUri = Uri.parse(pathDec)
                    break
                } catch (e: UnsupportedEncodingException) {
                    e.printStackTrace()
                }
            }
        }
        return resultUri
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        // Inflate the menu; this adds items to the action bar if it is present.
        menuInflater.inflate(R.menu.menu_share, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        // Respond to the action bar's Up/Home button
        if (item.itemId == android.R.id.home) {
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    @Throws(FileNotFoundException::class)
    fun generateProof(button: View?) {
        findViewById<View>(R.id.view_proof_progress).visibility = View.VISIBLE
        findViewById<View>(R.id.view_no_proof).visibility = View.GONE

        // Get intent, action and MIME type
        val intent = intent
        val action = intent.action
        var proofHash: String? = null
        if (Intent.ACTION_SEND_MULTIPLE == action) {
            val mediaUris = intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM) ?: emptyList()
            object : AsyncTask<Void?, Void?, String?>() {
                override fun doInBackground(vararg voids: Void?): String? {
                    var proofHash: String? = null
                    for (mediaUri in mediaUris!!) {
                        try {
                            proofHash = HashUtils.getSHA256FromFileContent(
                                contentResolver.openInputStream(mediaUri)
                            )
                            hashCache[mediaUri.toString()] = proofHash
                            val genProofHash = ProofMode.generateProof(
                                this@ShareProofActivity,
                                mediaUri,
                                proofHash
                            )
                            if (genProofHash != null && genProofHash == proofHash) {
                                //all good
                            } else {
                                //error occured
                            }
                        } catch (fe: FileNotFoundException) {
                            Timber.d("FileNotFound: %s", mediaUri)
                        }
                    }
                    return proofHash
                }

                override fun onPostExecute(proofHash: String?) {
                    super.onPostExecute(proofHash)
                    if (proofHash != null) displaySharePrompt() else showProofError()
                }


            }.execute()
        } else if (Intent.ACTION_SEND == action || action!!.endsWith("SHARE_PROOF")) {
            var mediaUri = intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
            if (mediaUri == null) mediaUri = intent.data
            if (mediaUri != null) {
                mediaUri = cleanUri(mediaUri)
                try {
                    proofHash = hashCache[mediaUri.toString()]
                    if (proofHash != null) proofHash = HashUtils.getSHA256FromFileContent(
                        contentResolver.openInputStream(mediaUri)
                    )
                    generateProof(mediaUri, proofHash)
                } catch (fe: FileNotFoundException) {
                    proofHash = null
                }
            }
        }
    }

    fun clickNotarize(button: View?) {
        shareProof(false, false)
    }

    fun clickAll(button: View?) {
        shareProof(sendMedia, true)
    }

    fun saveAll(button: View?) {
        saveProof(sendMedia, true)
    }

    private fun displayProgress(text: String) {
        binding.apply {
            viewProofProgress.visibility = View.VISIBLE
            viewNoProof.visibility = View.GONE
            viewProof.visibility = View.GONE
            if (!TextUtils.isEmpty(text)) {
                progressText.visibility = View.VISIBLE
                progressText.text = text
            } else progressText.visibility = View.GONE
        }

    }

    private fun displayGeneratePrompt() {
        binding.apply {
            viewProofProgress.visibility = View.GONE
            viewNoProof.visibility = View.VISIBLE
            viewProof.visibility = View.GONE
        }

    }

    private fun displaySharePrompt() {
        binding.apply {
            viewProofProgress.visibility = View.GONE
            viewNoProof.visibility = View.GONE
            viewProof.visibility = View.VISIBLE
        }
    }

    private fun saveProof(shareMedia: Boolean, shareProof: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                generateProofZipName()
                showSaveDirectoryPicker(proofZipName)
            } catch (e: PGPException) {
                e.printStackTrace()
            } catch (e: IOException) {
                e.printStackTrace()
            }
        } else {
            if (!askForWritePermissions()) {
                displayProgress(getString(R.string.progress_building_proof))
                SaveProofTask(this).execute(shareMedia, shareProof)
            }
        }
    }

    private var baseDocumentTreeUri: Uri? = null
    private var mStartForResult = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            baseDocumentTreeUri = Objects.requireNonNull(
                result.data!!.data
            )
            val takeFlags =
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION

            // take persistable Uri Permission for future use
            contentResolver.takePersistableUriPermission(result.data!!.data!!, takeFlags)
            SaveProofTask(this@ShareProofActivity).execute(true, true)
        }
    }

    private fun showSaveDirectoryPicker(fileName: String) {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
        mStartForResult.launch(intent)
    }

    private var proofZipName = ""
    @Throws(PGPException::class, IOException::class)
    private fun generateProofZipName() {
        val sdf = SimpleDateFormat(ZIP_FILE_DATETIME_FORMAT)
        val dateString = sdf.format(Date())
        val userId = pgpUtils.publicKeyFingerprint
        proofZipName = "proofmode-$userId-$dateString.zip"
    }

    @Synchronized
    @Throws(IOException::class, PGPException::class)
    private fun saveProofAsync(shareMedia: Boolean, shareProof: Boolean): File? {

        // Get intent, action and MIME type
        val intent = intent
        val action = intent.action
        val shareUris = ArrayList<Uri?>()
        val shareText = StringBuffer()
        var fileProofDownloads: File? = null
        if (Intent.ACTION_SEND_MULTIPLE == action) {
            val mediaUris = intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM) ?: emptyList()
            val fileBatchProof: File
            val fBatchProofOut: PrintWriter
            try {
                val fileFolder = MediaWatcher.getHashStorageDir(this, "batch") ?: return null
                fileBatchProof = File(fileFolder, Date().time.toString() + "batchproof.csv")
                fBatchProofOut = PrintWriter(FileWriter(fileBatchProof, true))
            } catch (ioe: IOException) {
                return null //unable to open batch proof
            }
            var successProof = 0
            var isFirstProof = true
            for (mediaUri in mediaUris) {
                if (processUri(
                        null,
                        mediaUri,
                        shareUris,
                        shareText,
                        fBatchProofOut,
                        shareMedia,
                        isFirstProof
                    )
                ) {
                    successProof++
                    isFirstProof = false
                } else {
                    Timber.d("share proof failed for: $mediaUri")
                }
            }
            fBatchProofOut.flush()
            fBatchProofOut.close()
            shareUris.add(Uri.fromFile(fileBatchProof)) // Add your image URIs here
        } else if (Intent.ACTION_SEND == action || action!!.endsWith("SHARE_PROOF")) {
            var mediaUri = intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
            if (mediaUri == null) mediaUri = intent.data
            if (mediaUri != null) {
                mediaUri = cleanUri(mediaUri)
                val mediaHash = hashCache[mediaUri.toString()]
                if (!processUri(
                        mediaHash,
                        mediaUri,
                        shareUris,
                        shareText,
                        null,
                        shareMedia,
                        true
                    )
                ) return null
            }
        }
        if (shareUris.size > 0) {
            if (!shareProof) shareNotarization(shareText.toString()) else {
                val fileCacheFolder = File(cacheDir, "zips")
                fileCacheFolder.mkdir()
                generateProofZipName()
                var fileZip = File(fileCacheFolder, proofZipName)
                Timber.d("Preparing proof bundle zip: " + fileZip.absolutePath)
                try {
                    zipProof(shareUris, fileZip)
                } catch (e: IOException) {
                    Timber.e(e, "Error generating proof Zip")
                    return null
                }
                if (fileZip.length() > 0) {
                    Timber.d("Proof zip completed. Size:%s", fileZip.length())
                    val encryptZip = false
                    if (encryptZip) {
                        val fileZipEnc = File(fileCacheFolder, "$proofZipName.gpg")
                        try {
                            pgpUtils.encrypt(
                                FileInputStream(fileZip),
                                fileZip.length(),
                                FileOutputStream(fileZipEnc)
                            )
                            fileZip = fileZipEnc
                        } catch (e: IOException) {
                            e.printStackTrace()
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    }
                    /**
                     * Uri uriZip = FileProvider.getUriForFile(this, BuildConfig.APPLICATION_ID + ".provider", fileZip);
                     * shareFiltered(getString(R.string.select_app), shareText.toString(), shareUris, uriZip);
                     */
                    if (baseDocumentTreeUri != null) {
                        try {
                            val fis = FileInputStream(fileZip)
                            val directory = DocumentFile.fromTreeUri(
                                this@ShareProofActivity,
                                baseDocumentTreeUri!!
                            )
                            val file = directory!!.createFile("application/zip", fileZip.name)
                            val pfd = contentResolver.openFileDescriptor(
                                file!!.uri, "w"
                            )
                            val fos = FileOutputStream(pfd!!.fileDescriptor)
                            try {
                                var count: Int
                                val data = ByteArray(BUFFER)
                                while (fis.read(data, 0, BUFFER).also { count = it } != -1) {
                                    fos.write(data, 0, count)
                                }
                                fis.close()
                                fos.close()
                            } catch (e: Exception) {
                                Timber.e(
                                    e,
                                    "Proof zip failed due to file copy:" + fileProofDownloads!!.absolutePath
                                )
                                return null
                            }
                            fos.close()
                        } catch (e: IOException) {
                            Timber.e(e)
                        }
                    } else {
                        fileProofDownloads = File(
                            Environment.getExternalStoragePublicDirectory(
                                Environment.DIRECTORY_DOWNLOADS
                            ), fileZip.name
                        )
                        val fos = FileOutputStream(fileProofDownloads)
                        val fis = FileInputStream(fileZip)
                        try {
                            var count: Int
                            val data = ByteArray(BUFFER)
                            while (fis.read(data, 0, BUFFER).also { count = it } != -1) {
                                fos.write(data, 0, count)
                            }
                            fis.close()
                            fos.close()
                        } catch (e: Exception) {
                            Timber.e(
                                e,
                                "Proof zip failed due to file copy:" + fileProofDownloads.absolutePath
                            )
                            return null
                        }
                    }

                    //copy IO utils
                } else {
                    Timber.d("Proof zip failed due to empty size:" + fileZip.length())
                    return null
                }
            }
        } else {
            return null
        }
        return fileProofDownloads
    }

    private fun shareProof(shareMedia: Boolean, shareProof: Boolean) {
        displayProgress(getString(R.string.progress_building_proof))
        ShareProofTask(this).execute(shareMedia, shareProof)
    }

    @Synchronized
    @Throws(IOException::class, PGPException::class)
    private fun shareProofAsync(shareMedia: Boolean, shareProof: Boolean): Boolean {

        // Get intent, action and MIME type
        val intent = intent
        val action = intent.action
        val shareUris = ArrayList<Uri?>()
        val shareText = StringBuffer()
        if (Intent.ACTION_SEND_MULTIPLE == action) {
            val mediaUris = intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM) ?: emptyList()
            val fileBatchProof: File
            val fBatchProofOut: PrintWriter
            try {
                val fileFolder = MediaWatcher.getHashStorageDir(this, "batch") ?: return false
                fileBatchProof = File(fileFolder, Date().time.toString() + "batchproof.csv")
                fBatchProofOut = PrintWriter(FileWriter(fileBatchProof, true))
            } catch (ioe: IOException) {
                return false //unable to open batch proof
            }
            var successProof = 0
            var isFirstProof = true
            for (mediaUri in mediaUris) {
                if (processUri(
                        null,
                        mediaUri,
                        shareUris,
                        shareText,
                        fBatchProofOut,
                        shareMedia,
                        isFirstProof
                    )
                ) {
                    successProof++
                    isFirstProof = false
                } else {
                    Timber.d("share proof failed for: $mediaUri")
                }
            }
            fBatchProofOut.flush()
            fBatchProofOut.close()
            shareUris.add(Uri.fromFile(fileBatchProof)) // Add your image URIs here
        } else if (Intent.ACTION_SEND == action || action!!.endsWith("SHARE_PROOF")) {
            var mediaUri = intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
            if (mediaUri == null) mediaUri = intent.data
            if (mediaUri != null) {
                mediaUri = cleanUri(mediaUri)
                val mediaHash = hashCache[mediaUri.toString()]
                if (!processUri(
                        mediaHash,
                        mediaUri,
                        shareUris,
                        shareText,
                        null,
                        shareMedia,
                        true
                    )
                ) return false
            }
        }
        if (shareUris.size > 0) {
            if (!shareProof) shareNotarization(shareText.toString()) else {
                val fileCacheFolder = File(cacheDir, "zips")
                fileCacheFolder.mkdir()
                val sdf = SimpleDateFormat(ZIP_FILE_DATETIME_FORMAT)
                val dateString = sdf.format(Date())
                val userId = pgpUtils.publicKeyFingerprint
                val fileZip = File(fileCacheFolder, "proofmode-$userId-$dateString.zip")
                Timber.d("Preparing proof bundle zip: " + fileZip.absolutePath)
                try {
                    zipProof(shareUris, fileZip)
                } catch (e: IOException) {
                    Timber.e(e, "Error generating proof Zip")
                    return false
                }
                if (fileZip.length() > 0) {
                    Timber.d("Proof zip completed. Size:" + fileZip.length())
                    val encryptZip = false
                    if (encryptZip) {
                        val fileZipEnc =
                            File(fileCacheFolder, "proofmode-$userId-$dateString.zip.gpg")
                        try {
                            pgpUtils.encrypt(
                                FileInputStream(fileZip),
                                fileZip.length(),
                                FileOutputStream(fileZipEnc)
                            )
                        } catch (e: IOException) {
                            e.printStackTrace()
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    }

                    val uriZip = FileProvider.getUriForFile(
                        this,
                        BuildConfig.APPLICATION_ID + ".provider",
                        fileZip
                    )
                    shareFiltered(
                        getString(R.string.select_app),
                        shareText.toString(),
                        shareUris,
                        uriZip
                    )
                } else {
                    Timber.d("Proof zip failed due to empty size:" + fileZip.length())
                    return false
                }
            }
        } else {
            return false
        }
        return true
    }

    @Throws(FileNotFoundException::class)
    private fun proofExists(mediaUri: Uri): String? {
        var mediaUri = mediaUri
        var sMediaUri = mediaUri.toString()
        if (sMediaUri.contains(DOCUMENT_IMAGE)) {
            sMediaUri = sMediaUri.replace(DOCUMENT_IMAGE, MEDIA_IMAGE)
            mediaUri = Uri.parse(sMediaUri)
        }
        val hash = HashUtils.getSHA256FromFileContent(
            contentResolver.openInputStream(mediaUri)
        )
        if (hash != null) {
            hashCache[sMediaUri] = hash
            Timber.d("Proof check if exists for URI %s and hash %s", mediaUri, hash)
            val fileFolder = MediaWatcher.getHashStorageDir(this, hash)
            return if (fileFolder != null) {
                val fileMediaProof = File(fileFolder, hash + ProofMode.PROOF_FILE_TAG)
                //generate now?
                if (fileMediaProof.exists()) hash else null
            } else null
        }
        return null
    }

    private class GenerateProofTask  // only retain a weak reference to the activity
        (private val activity: ShareProofActivity, private val proofHash: String?) :
        AsyncTask<Uri?, Void?, String?>() {
        override fun doInBackground(vararg params: Uri?): String? {
            return ProofMode.generateProof(activity, params[0], proofHash)
        }

        override fun onPostExecute(proofMediaHash: String?) {
            if (proofMediaHash != null) activity.displaySharePrompt() else {
                activity.showProofError()
            }
        }
    }

    private class CheckProofTasks(private val activity: ShareProofActivity) :
        AsyncTask<Uri?, Void?, String?>() {
        override fun doInBackground(vararg params: Uri?): String? {
            val mediaUri = params[0]
            var proofHash: String? = null
            if (mediaUri != null) {
                //mediaUri = activity.cleanUri (mediaUri);
                proofHash = try {
                    activity.proofExists(mediaUri)
                } catch (e: FileNotFoundException) {
                    Timber.w(e)
                    null
                }
            }
            return proofHash
        }

        override fun onPostExecute(proofHash: String?) {
            if (proofHash != null) {
                activity.displaySharePrompt()
            } else {
                activity.displayGeneratePrompt()
            }
        }
    }

    private class SaveProofTask  // only retain a weak reference to the activity
        (private val activity: ShareProofActivity) :
        AsyncTask<Boolean?, Void?, File?>() {
         override fun doInBackground(vararg params: Boolean?): File? {
            var result: File? = null
            return try {

                params.let {
                    result = activity.saveProofAsync(params[0]!!, params[1]!!)
                }
                return result
            } catch (e: IOException) {
                Timber.e(e, "error saving proof")
                null
            } catch (e: PGPException) {
                Timber.e(e, "error saving proof")
                null
            }
        }

        override fun onPostExecute(result: File?) {
            if (result == null) {
                //do something
                Timber.d("unable to shareProofAsync")
                activity.showProofError()
            } else {
                activity.showProofSaved(result)
            }
        }
    }

    private class ShareProofTask  // only retain a weak reference to the activity
    internal constructor(private val activity: ShareProofActivity) :
        AsyncTask<Boolean?, Void?, Boolean>() {
        override fun doInBackground(vararg params: Boolean?): Boolean {
            var result = false
            return try {
                result = activity.shareProofAsync(params[0]!!, params[1]!!)
                result
            } catch (e: IOException) {
                Timber.e(e, "error sharing proof")
                false
            } catch (e: PGPException) {
                Timber.e(e, "error sharing proof")
                false
            }
        }

        override fun onPostExecute(result: Boolean) {
            if (!result) {
                //do something
                Timber.d("unable to shareProofAsync")
                activity.showProofError()
            } else {
                //    activity.finish();
            }
        }
    }

    private fun generateProof(mediaUri: Uri?, proofHash: String?) {
        displayProgress(getString(R.string.progress_generating_proof))
        GenerateProofTask(this, proofHash).execute(mediaUri)
    }

    private fun showProofError() {
        findViewById<View>(R.id.view_proof).visibility = View.GONE
        findViewById<View>(R.id.view_no_proof).visibility = View.GONE
        findViewById<View>(R.id.view_proof_progress).visibility = View.GONE
        findViewById<View>(R.id.view_proof_saved).visibility = View.GONE
        findViewById<View>(R.id.view_proof_failed).visibility = View.VISIBLE
    }

    private fun showProofSaved(fileProof: File) {
        findViewById<View>(R.id.view_proof).visibility = View.GONE
        findViewById<View>(R.id.view_no_proof).visibility = View.GONE
        findViewById<View>(R.id.view_proof_progress).visibility = View.GONE
        findViewById<View>(R.id.view_proof_failed).visibility = View.GONE
        findViewById<View>(R.id.view_proof_saved).visibility = View.VISIBLE
        val tv = findViewById<TextView>(R.id.view_proof_saved_message)
        tv.text = """
            ${getString(R.string.share_save_downloads)}
            
            ${fileProof.absolutePath}
            """.trimIndent()
    }

    private fun getRealUri(contentUri: Uri?): Uri? {
        val unusablePath = contentUri!!.path
        val startIndex = unusablePath!!.indexOf("external/")
        val endIndex = unusablePath.indexOf("/ACTUAL")
        return if (startIndex != -1 && endIndex != -1) {
            val embeddedPath = unusablePath.substring(startIndex, endIndex)
            val builder = contentUri.buildUpon()
            builder.path(embeddedPath)
            builder.authority("media")
            builder.build()
        } else contentUri
    }

    @Throws(IOException::class, PGPException::class)
    private fun processUri(
        mediaHash: String?,
        mediaUri: Uri?,
        shareUris: ArrayList<Uri?>,
        sb: StringBuffer,
        fBatchProofOut: PrintWriter?,
        shareMedia: Boolean,
        isFirstProof: Boolean
    ): Boolean {
        val projection = arrayOfNulls<String>(1)
        val mimeType = contentResolver.getType(mediaUri!!)
        if (mimeType != null) {
            if (mimeType.startsWith("image")) projection[0] =
                MediaStore.Images.Media.DATA else if (mimeType.startsWith("video")) projection[0] =
                MediaStore.Video.Media.DATA else if (mimeType.startsWith("audio")) projection[0] =
                MediaStore.Audio.Media.DATA
        } else projection[0] = MediaStore.Images.Media.DATA
        val cursor = contentResolver.query(getRealUri(mediaUri)!!, projection, null, null, null)
        var result = false
        var mediaPath: String? = null
        if (cursor != null) {
            if (cursor.count > 0) {
                cursor.moveToFirst()
                try {
                    val columnIndex = cursor.getColumnIndexOrThrow(projection[0])
                    mediaPath = cursor.getString(columnIndex)
                } catch (e: Exception) {
                    //couldn't find path
                }
            }
            cursor.close()
        }
        if (TextUtils.isEmpty(mediaPath)) {
            val fileMedia = File(mediaUri.path)
            if (fileMedia.exists()) mediaPath = fileMedia.absolutePath
        }
        if (mediaPath != null) {
            //check proof metadata against original image
            val fileMedia = File(mediaPath)
            result = shareProof(
                mediaHash,
                mediaUri,
                fileMedia,
                shareUris,
                sb,
                fBatchProofOut,
                shareMedia,
                isFirstProof
            )
            if (!result) result =
                shareProofClassic(mediaUri, mediaPath, shareUris, sb, fBatchProofOut, shareMedia)
        } else {
            result = shareProof(
                mediaHash,
                mediaUri,
                null,
                shareUris,
                sb,
                fBatchProofOut,
                shareMedia,
                isFirstProof
            )
        }
        return result
    }

    @Throws(IOException::class, PGPException::class)
    private fun shareProof(
        hash: String?,
        uriMedia: Uri?,
        fileMedia: File?,
        shareUris: ArrayList<Uri?>,
        sb: StringBuffer,
        fBatchProofOut: PrintWriter?,
        shareMedia: Boolean,
        isFirstProof: Boolean
    ): Boolean {
        var hash = hash
        if (hash == null) hash = HashUtils.getSHA256FromFileContent(
            contentResolver.openInputStream(
                uriMedia!!
            )
        )
        if (hash != null) {
            val fileFolder = MediaWatcher.getHashStorageDir(this, hash) ?: return false
            val fileMediaSig = File(fileFolder, hash + ProofMode.OPENPGP_FILE_TAG)
            val fileMediaProof = File(fileFolder, hash + ProofMode.PROOF_FILE_TAG)
            val fileMediaProofSig =
                File(fileFolder, hash + ProofMode.PROOF_FILE_TAG + ProofMode.OPENPGP_FILE_TAG)
            val fileMediaProofJSON = File(fileFolder, hash + ProofMode.PROOF_FILE_JSON_TAG)
            val fileMediaProofJSONSig =
                File(fileFolder, hash + ProofMode.PROOF_FILE_JSON_TAG + ProofMode.OPENPGP_FILE_TAG)
            val fileMediaOpentimestamps = File(fileFolder, hash + ProofMode.OPENTIMESTAMPS_FILE_TAG)
            val fileMediaGoogleSafetyNet =
                File(fileFolder, hash + ProofMode.GOOGLE_SAFETYNET_FILE_TAG)
            if (fileMediaProof.exists()) {
                var lastModified: Date? = null
                if (fileMedia != null) lastModified = Date(fileMedia.lastModified())
                generateProofOutput(
                    uriMedia,
                    fileMedia,
                    lastModified,
                    fileMediaSig,
                    fileMediaProof,
                    fileMediaProofSig,
                    fileMediaProofJSON,
                    fileMediaProofJSONSig,
                    fileMediaOpentimestamps,
                    fileMediaGoogleSafetyNet,
                    hash,
                    shareMedia,
                    fBatchProofOut,
                    shareUris,
                    sb,
                    isFirstProof
                )
                return true
            }
        }
        return false
    }

    @Throws(IOException::class, PGPException::class)
    private fun shareProofClassic(
        mediaUri: Uri?,
        mediaPath: String,
        shareUris: ArrayList<Uri?>,
        sb: StringBuffer,
        fBatchProofOut: PrintWriter?,
        shareMedia: Boolean
    ): Boolean {
        val baseFolder = "proofmode"
        val hash = HashUtils.getSHA256FromFileContent(
            contentResolver.openInputStream(
                mediaUri!!
            )
        )
        val fileMedia = File(mediaPath)
        var fileMediaSig = File(mediaPath + ProofMode.OPENPGP_FILE_TAG)
        var fileMediaProof = File(mediaPath + ProofMode.PROOF_FILE_TAG)
        var fileMediaProofSig = File(fileMediaProof.absolutePath + ProofMode.OPENPGP_FILE_TAG)

        //if not there try alternate locations
        if (!fileMediaSig.exists()) {
            fileMediaSig =
                File(Environment.getExternalStorageDirectory(), "$baseFolder$mediaPath.asc")
            fileMediaProof = File(
                Environment.getExternalStorageDirectory(),
                baseFolder + mediaPath + ProofMode.PROOF_FILE_TAG
            )
            fileMediaProofSig = File(fileMediaProof.absolutePath + ProofMode.OPENPGP_FILE_TAG)
            if (!fileMediaSig.exists()) {
                fileMediaSig =
                    File(getExternalFilesDir(null), mediaPath + ProofMode.OPENPGP_FILE_TAG)
                fileMediaProof =
                    File(getExternalFilesDir(null), mediaPath + ProofMode.PROOF_FILE_TAG)
                fileMediaProofSig = File(fileMediaProof.absolutePath + ProofMode.OPENPGP_FILE_TAG)
            }
        }
        generateProofOutput(
            mediaUri,
            fileMedia,
            Date(fileMedia.lastModified()),
            fileMediaSig,
            fileMediaProof,
            fileMediaProofSig,
            null,
            null,
            null,
            null,
            hash,
            shareMedia,
            fBatchProofOut,
            shareUris,
            sb,
            true
        )
        return true
    }

    @Throws(IOException::class, PGPException::class)
    private fun generateProofOutput(
        uriMedia: Uri?,
        fileMedia: File?,
        fileLastModified: Date?,
        fileMediaSig: File?,
        fileMediaProof: File,
        fileMediaProofSig: File?,
        fileMediaProofJSON: File?,
        fileMediaProofJSONSig: File?,
        fileMediaNotary: File?,
        fileMediaNotary2: File?,
        hash: String,
        shareMedia: Boolean,
        fBatchProofOut: PrintWriter?,
        shareUris: ArrayList<Uri?>,
        sb: StringBuffer,
        isFirstProof: Boolean
    ) {
        val sdf = SimpleDateFormat.getDateTimeInstance()
        val fingerprint = pgpUtils.publicKeyFingerprint
        if (fileMedia != null) {
            sb.append(fileMedia.name).append(' ')
            sb.append(getString(R.string.last_modified)).append(' ')
                .append(sdf.format(fileLastModified))
            sb.append(' ')
        }
        sb.append(getString(R.string.has_hash)).append(' ').append(hash)
        sb.append("\n\n")
        sb.append(getString(R.string.proof_signed)).append(fingerprint)
        sb.append("\n")

        //shareUris.add(FileProvider.getUriForFile(this, BuildConfig.APPLICATION_ID + PROVIDER_TAG,fileMediaProof));
        shareUris.add(Uri.fromFile(fileMediaProof))
        if (shareMedia) {
            shareUris.add(uriMedia)
            if (fileMediaSig != null
                && fileMediaSig.exists()
            ) shareUris.add(Uri.fromFile(fileMediaSig))
            if (fileMediaProofSig != null
                && fileMediaProofSig.exists()
            ) shareUris.add(Uri.fromFile(fileMediaProofSig))
            if (fileMediaProofJSON != null
                && fileMediaProofJSON.exists()
            ) shareUris.add(Uri.fromFile(fileMediaProofJSON))
            if (fileMediaProofJSONSig != null
                && fileMediaProofJSONSig.exists()
            ) shareUris.add(Uri.fromFile(fileMediaProofJSONSig))
            if (fileMediaNotary != null
                && fileMediaNotary.exists()
            ) shareUris.add(Uri.fromFile(fileMediaNotary))
            if (fileMediaNotary2 != null
                && fileMediaNotary2.exists()
            ) shareUris.add(Uri.fromFile(fileMediaNotary2))
        }
        if (fBatchProofOut != null) {
            val br = BufferedReader(FileReader(fileMediaProof))
            if (!isFirstProof) {
                br.readLine() //skip header
            } else {
                //get header from proof
                fBatchProofOut.println(br.readLine())
            }
            val csvLine = br.readLine()
            fBatchProofOut.println(csvLine)
            br.close()
        }
    }

    private fun shareNotarization(shareText: String) {
        val shareIntent = Intent(Intent.ACTION_SEND)
        shareIntent.putExtra(Intent.EXTRA_TEXT, shareText)
        shareIntent.type = "*/*"
        startActivity(Intent.createChooser(shareIntent, getString(R.string.share_notarization)))
    }

    private fun shareFilteredSingle(
        shareMessage: String,
        shareText: String,
        shareUri: Uri,
        shareMimeType: String
    ) {
        val pm = packageManager
        val sendIntent = Intent(Intent.ACTION_SEND)
        sendIntent.type = "*/*"
        val resInfo = pm.queryIntentActivities(sendIntent, 0)
        val intentList = ArrayList<LabeledIntent>()
        for (i in resInfo.indices) {
            // Extract the label, append it, and repackage it in a LabeledIntent
            val ri = resInfo[i]
            val packageName = ri.activityInfo.packageName
            if (packageName.contains("android.email")) {
                val intent = Intent()
                intent.setPackage(packageName)
                intent.setDataAndType(shareUri, shareMimeType)
                intent.putExtra(Intent.EXTRA_STREAM, shareUri)
                intent.putExtra(Intent.EXTRA_TITLE, shareUri.lastPathSegment)
                intent.putExtra(Intent.EXTRA_SUBJECT, shareUri.lastPathSegment)
                intentList.add(
                    LabeledIntent(
                        intent, packageName, ri
                            .loadLabel(pm), ri.icon
                    )
                )
            } else if (packageName.contains("com.whatsapp")) {
                val intent = Intent()
                intent.component = ComponentName(
                    packageName,
                    ri.activityInfo.name
                )
                intent.action = Intent.ACTION_SEND
                intent.setDataAndType(shareUri, shareMimeType)
                intent.putExtra(Intent.EXTRA_TEXT, shareText)
                intent.putExtra(Intent.EXTRA_STREAM, shareUri)
                intentList.add(
                    LabeledIntent(
                        intent, packageName, ri
                            .loadLabel(pm), ri.icon
                    )
                )
            } else if (packageName.contains("com.google.android.gm")) {
                val intent = Intent()
                intent.component = ComponentName(
                    packageName,
                    ri.activityInfo.name
                )
                intent.action = Intent.ACTION_SEND
                //       intent.setDataAndType(shareUri, shareMimeType);
                intent.putExtra(Intent.EXTRA_EMAIL, arrayOf(""))
                intent.putExtra(Intent.EXTRA_TEXT, shareText)
                intent.putExtra(Intent.EXTRA_STREAM, shareUri)
                intent.putExtra(Intent.EXTRA_TITLE, shareUri.lastPathSegment)
                intent.putExtra(Intent.EXTRA_SUBJECT, shareUri.lastPathSegment)
                intentList.add(
                    LabeledIntent(
                        intent, packageName, ri
                            .loadLabel(pm), ri.icon
                    )
                )
            } else if (packageName.contains("com.google.android.apps.docs")) {
                val intent = Intent()
                intent.component = ComponentName(
                    packageName,
                    ri.activityInfo.name
                )
                intent.action = Intent.ACTION_SEND
                //     intent.setDataAndType(shareUri, shareMimeType);
                intent.putExtra(Intent.EXTRA_TEXT, shareText)
                intent.putExtra(Intent.EXTRA_STREAM, shareUri)
                intent.putExtra(Intent.EXTRA_TITLE, shareUri.lastPathSegment)
                intent.putExtra(Intent.EXTRA_SUBJECT, shareUri.lastPathSegment)
                intentList.add(
                    LabeledIntent(
                        intent, packageName, ri
                            .loadLabel(pm), ri.icon
                    )
                )
            } else if (packageName.contains("com.dropbox")) {
                val intent = Intent()
                intent.component = ComponentName(
                    packageName,
                    ri.activityInfo.name
                )
                intent.action = Intent.ACTION_SEND
                //    intent.setDataAndType(shareUri, shareMimeType);
                intent.putExtra(Intent.EXTRA_TEXT, shareText)
                intent.putExtra(Intent.EXTRA_STREAM, shareUri)
                intent.putExtra(Intent.EXTRA_TITLE, shareUri.lastPathSegment)
                intent.putExtra(Intent.EXTRA_SUBJECT, shareUri.lastPathSegment)
                intentList.add(
                    LabeledIntent(
                        intent, packageName, ri
                            .loadLabel(pm), ri.icon
                    )
                )
            } else if (packageName.contains("org.thoughtcrime")) {
                val intent = Intent()
                intent.component = ComponentName(
                    packageName,
                    ri.activityInfo.name
                )
                intent.action = Intent.ACTION_SEND
                //     intent.setDataAndType(shareUri, shareMimeType);
                intent.putExtra(Intent.EXTRA_TEXT, shareText)
                intent.putExtra(Intent.EXTRA_STREAM, shareUri)
                intent.putExtra(Intent.EXTRA_TITLE, shareUri.lastPathSegment)
                intent.putExtra(Intent.EXTRA_SUBJECT, shareUri.lastPathSegment)
                intentList.add(
                    LabeledIntent(
                        intent, packageName, ri
                            .loadLabel(pm), ri.icon
                    )
                )
            } else if (packageName.contains("conversations")) {
                val intent = Intent()
                intent.component = ComponentName(
                    packageName,
                    ri.activityInfo.name
                )
                intent.action = Intent.ACTION_SEND
                intent.setDataAndType(shareUri, shareMimeType)
                intent.putExtra(Intent.EXTRA_TEXT, shareText)
                intent.putExtra(Intent.EXTRA_STREAM, shareUri)
                intent.putExtra(Intent.EXTRA_TITLE, shareUri.lastPathSegment)
                intent.putExtra(Intent.EXTRA_SUBJECT, shareUri.lastPathSegment)
                intentList.add(
                    LabeledIntent(
                        intent, packageName, ri
                            .loadLabel(pm), ri.icon
                    )
                )
            } else if (packageName.contains("org.awesomeapp") || packageName.contains("im.zom")) {
                val intent = Intent()
                intent.component = ComponentName(
                    packageName,
                    ri.activityInfo.name
                )
                intent.action = Intent.ACTION_SEND
                intent.setDataAndType(shareUri, shareMimeType)
                intent.putExtra(Intent.EXTRA_TEXT, shareText)
                intent.putExtra(Intent.EXTRA_STREAM, shareUri)
                intent.putExtra(Intent.EXTRA_TITLE, shareUri.lastPathSegment)
                intent.putExtra(Intent.EXTRA_SUBJECT, shareUri.lastPathSegment)
                intentList.add(
                    LabeledIntent(
                        intent, packageName, ri
                            .loadLabel(pm), ri.icon
                    )
                )
            }
        }
        val baseIntent = Intent()
        baseIntent.action = Intent.ACTION_SEND

        // convert intentList to array
        val extraIntents = intentList
            .toTypedArray()
        val openInChooser = Intent.createChooser(baseIntent, shareMessage)
        openInChooser.putExtra(Intent.EXTRA_INITIAL_INTENTS, extraIntents)
        startActivity(openInChooser)
    }

    private fun shareFiltered(
        shareMessage: String,
        shareText: String,
        shareUris: ArrayList<Uri?>?,
        shareZipUri: Uri?
    ) {
        val modeFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION
        val shareIntent = Intent()
        shareIntent.action = Intent.ACTION_SEND
        shareIntent.setDataAndType(shareZipUri, "application/zip")
        shareIntent.putExtra(Intent.EXTRA_TEXT, shareText)
        shareIntent.putExtra(Intent.EXTRA_STREAM, shareZipUri)
        shareIntent.addFlags(modeFlags)
        val openInChooser = Intent.createChooser(shareIntent, shareMessage)
        openInChooser.addFlags(modeFlags)
        val resInfoList = this.packageManager.queryIntentActivities(openInChooser, 0)
        for (resolveInfo in resInfoList) {
            val packageName = resolveInfo.activityInfo.packageName
            if (shareUris != null) for (uri in shareUris) grantUriPermission(
                packageName,
                uri,
                modeFlags
            )
            shareZipUri?.let { grantUriPermission(packageName, it, modeFlags) }
        }
        startActivity(openInChooser)
    }

    private fun askForPermission(permission: String, requestCode: Int) {
        if (ContextCompat.checkSelfPermission(
                this,
                permission
            ) != PackageManager.PERMISSION_GRANTED
        ) {

            // Should we show an explanation?
            ActivityCompat.requestPermissions(this, arrayOf(permission), requestCode)
        }
    }

    @Throws(IOException::class, PGPException::class)
    fun zipProof(uris: ArrayList<Uri?>, fileZip: File?) {
        var origin: BufferedInputStream
        val dest = FileOutputStream(fileZip)
        val out = ZipOutputStream(
            BufferedOutputStream(
                dest
            )
        )
        val data = ByteArray(BUFFER)
        for (uri in uris) {
            try {
                val fileName = getFileNameFromUri(uri)
                Timber.d("adding to zip: $fileName")
                origin = BufferedInputStream(contentResolver.openInputStream(uri!!), BUFFER)
                val entry = ZipEntry(fileName)
                out.putNextEntry(entry)
                var count: Int
                while (origin.read(data, 0, BUFFER).also { count = it } != -1) {
                    out.write(data, 0, count)
                }
                origin.close()
            } catch (e: Exception) {
                Timber.d(e, "Failed adding URI to zip: " + uri!!.lastPathSegment)
            }
        }
        Timber.d("Adding public key")
        //add public key
        val pubKey = ProofMode.getPublicKeyString(this, "")
        var entry: ZipEntry? = ZipEntry("pubkey.asc")
        out.putNextEntry(entry)
        out.write(pubKey.toByteArray())
        Timber.d("Adding HowToVerifyProofData.txt")
        val howToFile = "HowToVerifyProofData.txt"
        entry = ZipEntry(howToFile)
        out.putNextEntry(entry)
        val `is` = resources.assets.open(howToFile)
        val buffer = ByteArray(1024)
        var length = `is`.read(buffer)
        while (length != -1) {
            out.write(buffer, 0, length)
            length = `is`.read(buffer)
        }
        `is`.close()
        Timber.d("Zip complete")
        out.close()
    }

    private fun getFileNameFromUri(uri: Uri?): String? {
        val projection = arrayOfNulls<String>(2)
        val mimeType = contentResolver.getType(uri!!)
        val mimeTypeMap = MimeTypeMap.getSingleton()
        val fileExt = mimeTypeMap.getExtensionFromMimeType(mimeType)
        if (mimeType != null) {
            if (mimeType.startsWith("image")) {
                projection[0] = MediaStore.Images.Media.DATA
                projection[1] = MediaStore.Images.Media.DISPLAY_NAME
            } else if (mimeType.startsWith("video")) {
                projection[0] = MediaStore.Video.Media.DATA
                projection[1] = MediaStore.Video.Media.DISPLAY_NAME
            } else if (mimeType.startsWith("audio")) {
                projection[0] = MediaStore.Audio.Media.DATA
                projection[1] = MediaStore.Audio.Media.DISPLAY_NAME
            }
        } else {
            projection[0] = MediaStore.Images.Media.DATA
            projection[1] = MediaStore.Images.Media.DISPLAY_NAME
        }
        val cursor = contentResolver.query(getRealUri(uri)!!, projection, null, null, null)
        val result = false

        //default name with file extension
        var fileName = uri.lastPathSegment
        if (fileExt != null && fileName!!.indexOf(".") == -1) fileName += ".$fileExt"
        if (cursor != null) {
            if (cursor.count > 0) {
                cursor.moveToFirst()
                try {
                    var columnIndex = cursor.getColumnIndexOrThrow(projection[0])
                    val path = cursor.getString(columnIndex)
                    if (path != null) {
                        val fileMedia = File(path)
                        if (fileMedia.exists()) fileName = fileMedia.name
                    }
                    if (TextUtils.isEmpty(fileName)) {
                        columnIndex = cursor.getColumnIndexOrThrow(projection[1])
                        fileName = cursor.getString(columnIndex)
                    }
                } catch (_: IllegalArgumentException) {
                }
            }
            cursor.close()
        }
        if (TextUtils.isEmpty(fileName)) fileName = uri.lastPathSegment
        return fileName
    }

    private fun showInfoBasic() {
        val builder = AlertDialog.Builder(this)
        builder.setView(R.layout.dialog_share_basic)
        val currentDialog: Dialog = builder.create()
        currentDialog.show()
        currentDialog.findViewById<View>(R.id.btnClose)
            .setOnClickListener { currentDialog.dismiss() }
        currentDialog.window!!.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
    }

    private fun showInfoRobust() {
        val builder = AlertDialog.Builder(this)
        builder.setView(R.layout.dialog_share_robust)
        val currentDialog: Dialog = builder.create()
        currentDialog.show()
        currentDialog.findViewById<View>(R.id.btnClose)
            .setOnClickListener { v: View? -> currentDialog.dismiss() }
        val checkBox = currentDialog.findViewById<CheckBox>(R.id.checkSendMedia)
        checkBox.isChecked = sendMedia
        checkBox.setOnCheckedChangeListener { _: CompoundButton?, isChecked: Boolean ->
            sendMedia = isChecked
        }
        currentDialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
    }

    /**
     * User the PermissionActivity to ask for permissions, but show no UI when calling from here.
     */
    private fun askForWritePermissions(): Boolean {
        val requiredPermissions = arrayOf(
            Manifest.permission.WRITE_EXTERNAL_STORAGE
        )
        if (!hasPermissions(this, requiredPermissions)) {
            val intent = Intent(this, PermissionActivity::class.java)
            intent.putExtra(PermissionActivity.ARG_PERMISSIONS, requiredPermissions)
            startActivityForResult(intent, 9999)
            return true
        }
        return false
    }

    companion object {
        private const val ZIP_FILE_DATETIME_FORMAT = "yyyy-MM-dd-HH-mm-ssz"
        private const val DOCUMENT_IMAGE =
            "content://com.android.providers.media.documents/document/image%3A"
        private const val MEDIA_IMAGE = "content://media/external/images/media/"
        private const val BUFFER = 1024 * 8
        fun writeToTempImageAndGetPathUri(inContext: Context, inImage: Bitmap): Uri {
            val bytes = ByteArrayOutputStream()
            inImage.compress(Bitmap.CompressFormat.JPEG, 100, bytes)
            val path = MediaStore.Images.Media.insertImage(
                inContext.contentResolver,
                inImage,
                "Title",
                null
            )
            return Uri.parse(path)
        }
    }
}