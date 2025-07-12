package org.witness.proofmode.org.witness.proofmode.share

import android.Manifest
import android.app.Dialog
import android.app.PendingIntent
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.*
import android.provider.MediaStore
import android.text.TextUtils
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.CheckBox
import android.widget.CompoundButton
import android.widget.EditText
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.core.net.toFile
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.lifecycleScope
import androidx.preference.PreferenceManager
import kotlinx.coroutines.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import org.bouncycastle.openpgp.PGPException
import org.witness.proofmode.PermissionActivity
import org.witness.proofmode.PermissionActivity.Companion.hasPermissions
import org.witness.proofmode.ProofMode
import org.witness.proofmode.ProofMode.PREF_OPTION_AI_DEFAULT
import org.witness.proofmode.ProofMode.PREF_OPTION_BLOCK_AI
import org.witness.proofmode.ProofMode.PREF_OPTION_CREDENTIALS
import org.witness.proofmode.ProofMode.PREF_OPTION_CREDENTIALS_DEFAULT
import org.witness.proofmode.ProofModeApp
import org.witness.proofmode.R
import org.witness.proofmode.c2pa.C2paUtils
import org.witness.proofmode.c2pa.C2paUtils.Companion.C2PA_CERT_PATH
import org.witness.proofmode.crypto.HashUtils
import org.witness.proofmode.crypto.pgp.PgpUtils
import org.witness.proofmode.databinding.ActivityShareBinding
import org.witness.proofmode.getFileNameFromUri
import org.witness.proofmode.getRealUri
import org.witness.proofmode.org.witness.proofmode.ui.ActivityConstants.EXTRA_FILE_NAME
import org.witness.proofmode.org.witness.proofmode.ui.ActivityConstants.EXTRA_SHARE_TEXT
import org.witness.proofmode.org.witness.proofmode.ui.ActivityConstants.INTENT_ACTIVITY_ITEMS_SHARED
import org.witness.proofmode.org.witness.proofmode.ui.ProofableItem
import org.witness.proofmode.storage.DefaultStorageProvider
import org.witness.proofmode.storage.StorageListener
import org.witness.proofmode.storage.StorageProvider
import org.witness.proofmode.storage.StorageProviderManager
import timber.log.Timber
import java.io.*
import java.net.URL
import java.net.URLDecoder
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream


class ShareProofActivity : AppCompatActivity() {
    private lateinit var binding: ActivityShareBinding
    private var sendMedia = true

    private val hashCache = HashMap<String, String?>()

    private lateinit var pgpUtils : PgpUtils

    private var mPrefs : SharedPreferences? = null
    private var mBlockAI : Boolean? = false

    private var mStorageProvider : DefaultStorageProvider? = null
    private var mC2PAEnable : Boolean? = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityShareBinding.inflate(layoutInflater)
        mPrefs = PreferenceManager.getDefaultSharedPreferences(this)
        pgpUtils = PgpUtils.getInstance()
        mBlockAI = mPrefs?.getBoolean(PREF_OPTION_BLOCK_AI, PREF_OPTION_AI_DEFAULT) == false
        mC2PAEnable = mPrefs?.getBoolean(PREF_OPTION_CREDENTIALS, PREF_OPTION_CREDENTIALS_DEFAULT) == true
        setContentView(binding.root)
        mStorageProvider = DefaultStorageProvider(applicationContext)
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
                checkProof(mediaUris[0])
            }
        } else if (Intent.ACTION_SEND == action || action!!.endsWith("SHARE_PROOF")) {
            intent.action = Intent.ACTION_SEND
            var mediaUri = intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
            if (mediaUri == null) mediaUri = intent.data
            if (mediaUri != null) {
                mediaUri = cleanUri(mediaUri)
                displayProgress(getString(R.string.progress_checking_proof))
                checkProof(mediaUri)
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

        if (resultUri.scheme == "file") {
            resultUri = FileProvider.getUriForFile(application, "${application.packageName}.provider", resultUri.toFile())
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
        binding.viewProofProgress.visibility = View.VISIBLE
        binding.viewNoProof.visibility = View.GONE

        // Get intent, action and MIME type
        val intent = intent
        val action = intent.action
        var proofHash: String? = null
        if (Intent.ACTION_SEND_MULTIPLE == action) {
            val mediaUris = intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM) ?: emptyList()

            generateMultiProof(mediaUris, mBlockAI == false, mStorageProvider)


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

    fun clickShareSocial (button: View?) {

        displayProgress("Preparing media for social share...")
        shareSocialAsync()

    }

    fun clickNotarize(button: View?) {
        shareProofWithProgress("", false, false)
    }

    fun clickAll(button: View?) {


        val inputEditTextField = EditText(this)
        val dialog = AlertDialog.Builder(this)
            .setTitle(getString(R.string.optional_filename))
            .setMessage(getString(R.string.set_a_custom_name_for_the_proof_zip))
            .setView(inputEditTextField)
            .setPositiveButton(getString(android.R.string.ok)) { _, _ ->
                val editTextInput = inputEditTextField .text.toString()
                shareProofWithProgress(editTextInput, sendMedia, true)
            }
            .setNegativeButton(getString(android.R.string.cancel))  { _, _ ->
                shareProofWithProgress("", sendMedia, true)
            }
            .create()
        dialog.show()



    }

    fun saveAll(button: View?) {

        val inputEditTextField = EditText(this)
        val dialog = AlertDialog.Builder(this)
            .setTitle(getString(R.string.optional_filename))
            .setMessage(getString(R.string.set_a_custom_name_for_the_proof_zip))
            .setView(inputEditTextField)
            .setPositiveButton(getString(android.R.string.ok)) { _, _ ->
                val editTextInput = inputEditTextField .text.toString()
               // shareProof(editTextInput, sendMedia, true)
                saveProof(editTextInput, sendMedia, true)
            }
            .setNegativeButton(getString(android.R.string.cancel))  { _, _ ->
                saveProof("", sendMedia, true)
            }
            .create()
        dialog.show()


    }

    private fun displayProgressAsync(progressText: String) {
        lifecycleScope.launch(Dispatchers.Main) {
            displayProgress(progressText)
        }
    }


    private fun shareProof(fileName: String, shareMedia: Boolean, shareProof: Boolean) {
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                try {
                    shareProofInternal(fileName, shareMedia, shareProof)
                } catch (e: IOException) {
                    Timber.e(e, "error sharing proof")
                    false
                } catch (e: PGPException) {
                    Timber.e(e, "error sharing proof")
                    false
                }
            }
            
            if (!result) {
                Timber.d("unable to shareProofInternal")
                showProofError()
            }
        }
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

    private fun saveProof(fileName: String, shareMedia: Boolean, shareProof: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                generateProofZipName(fileName)
                showSaveDirectoryPicker(proofZipName)
            } catch (e: PGPException) {
                e.printStackTrace()
            } catch (e: IOException) {
                e.printStackTrace()
            }
        } else {
            if (!askForWritePermissions()) {
                displayProgress(getString(R.string.progress_building_proof))
                saveProofInternal(fileName, shareMedia, shareProof)
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
            saveProofInternal(proofZipName, true, true)
        }
    }

    private fun showSaveDirectoryPicker(fileName: String) {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
        mStartForResult.launch(intent)
    }

    private var proofZipName = ""
    @Throws(PGPException::class, IOException::class)
    private fun generateProofZipName(fileName: String) {
        val sdf = SimpleDateFormat(ZIP_FILE_DATETIME_FORMAT)
        val dateString = sdf.format(Date())
        val userId = pgpUtils.publicKeyFingerprint

        if (fileName.isEmpty())
            proofZipName = "proofmode-0x$userId-$dateString.zip"
        else
            proofZipName = "$fileName-proofmode-0x$userId-$dateString.zip"
    }

    private fun shareSocial() : Boolean {

        // Get intent, action and MIME type
        val intent = intent
        val action = intent.action
        val shareUris = ArrayList<Uri?>()
        var lastMediaUri : Uri? = null

        if (Intent.ACTION_SEND_MULTIPLE == action) {
            val mediaUris =
                intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM) ?: emptyList()

            for (mediaUri in mediaUris) {

                shareUris.add(cleanUri(mediaUri))
                lastMediaUri = mediaUri
            }

        } else if (Intent.ACTION_SEND == action) {
            lastMediaUri = intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)

            if (lastMediaUri == null) lastMediaUri = intent.data


        }

        var mediaPublicUri = ""
        var shareString = ""

        if (shareUris.size > 1) {
            shareMedia(
                this,
                shareString,
                shareUris
            )
        }
        else
        {



              var spm = StorageProviderManager.getInstance()
              if (spm.isFilebaseEnabled())
              {
                    spm.getFilebaseProvider().let { fp ->

                        val hash = HashUtils.getSHA256FromFileContent(
                            lastMediaUri?.let { it1 -> contentResolver.openInputStream(it1) }
                        )

                        val publicIs =
                            spm.getPrimaryStorageProvider()?.getInputStream(hash, "$hash.public")
                        if (publicIs != null) {
                            val uri = publicIs.bufferedReader().use(BufferedReader::readText)
                            val verifyUri = "https://check.proofmode.org/#$uri"
                            shareString = getString(R.string.verify_this_media_at) + "$verifyUri #proofmode #c2pa"

                            lastMediaUri?.let { uri ->
                                val uriShareImage = SocialImageUtil().createImageCard(this@ShareProofActivity, uri, verifyUri)
                                shareUris.clear()
                                shareUris.add(cleanUri(uriShareImage))
                            }

                            shareMedia(
                                this@ShareProofActivity,
                                shareString,
                                shareUris
                            )

                            return true
                        }

                        hash.let {
                            var isMedia = contentResolver.openInputStream(lastMediaUri!!)
                            fp?.saveStream(hash, "public",isMedia!!, object : StorageListener {
                                override fun saveSuccessful(hash: String?, uri: String?) {

                                    spm.getPrimaryStorageProvider()?.saveText(hash,
                                        "$hash.public", uri, null)

                                    //Log.d(TAG, "Successfully saved $identifier to secondary storage at: $uri")
                                    val verifyUri = "https://check.proofmode.org/#$uri"
                                    shareString = getString(R.string.verify_this_media_at) + "$verifyUri #proofmode #c2pa"

                                    lastMediaUri?.let { uri ->
                                        val uriShareImage = SocialImageUtil().createImageCard(this@ShareProofActivity, uri, verifyUri)
                                        shareUris.clear()
                                        shareUris.add(cleanUri(uriShareImage))
                                    }

                                    shareMedia(
                                        this@ShareProofActivity,
                                        shareString,
                                        shareUris
                                    )
                                }

                                override fun saveFailed(exception: Exception?) {
                                //    Log.w(TAG, "Failed to save $identifier to secondary storage: ${exception?.message}")
                                }
                            })
                        }
                    }
              }
              else
              {
                  lastMediaUri?.let { uri ->
                      val uriShareImage = SocialImageUtil().createImageCard(this, uri, null)
                      shareUris.clear()
                      shareUris.add(cleanUri(uriShareImage))
                  }

                  shareMedia(
                      this,
                      shareString,
                      shareUris
                  )
              }

            return true
        }

        return true
    }

        @Synchronized
    @Throws(IOException::class, PGPException::class)
    private fun saveProofInternal(fileName: String, shareMedia: Boolean, shareProof: Boolean) {

        /**
            lifecycleScope.launch {
                val result = withContext(Dispatchers.IO) {
                    try {
                        saveProofInternal(fileName, shareMedia, shareProof)
                    } catch (e: IOException) {
                        Timber.e(e, "error saving proof")
                        null
                    } catch (e: PGPException) {
                        Timber.e(e, "error saving proof")
                        null
                    }
                }

                if (result?.isEmpty() == true) {
                    Timber.d("unable to saveProofInternal")
                    showProofError()
                } else {
                    showProofSaved(result)
                }
            }**/


        // Get intent, action and MIME type
        val intent = intent
        val action = intent.action
        val shareUris = ArrayList<Uri?>()
        val shareItems = ArrayList<ProofableItem>()
        val shareText = StringBuffer()
        var fileProofDownloads: File? = null

        if (Intent.ACTION_SEND_MULTIPLE == action) {
            val mediaUris = intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM) ?: emptyList()
            val fileBatchProof: File
            val fBatchProofOut: PrintWriter
            fileBatchProof = File(cacheDir, Date().time.toString() + "batchproof.csv")
            fBatchProofOut = PrintWriter(FileWriter(fileBatchProof, true))

            var successProof = 0
            var isFirstProof = true
            for (mediaUri in mediaUris) {
                shareUris.add(mediaUri)
                if (processUri(
                        null,
                        mediaUri,
                        shareUris,
                        shareItems,
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
        }

        else if (Intent.ACTION_SEND == action || action!!.endsWith("SHARE_PROOF")) {
            var mediaUri = intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
            if (mediaUri == null) mediaUri = intent.data

            mediaUri = cleanUri(mediaUri!!)
            shareUris.add(mediaUri)
            val mediaHash = hashCache[mediaUri.toString()]
            processUri(
                    mediaHash,
                    mediaUri,
                    shareUris,
                    shareItems,
                    shareText,
                    null,
                    shareMedia,
                    true
                )

        }

        if (shareUris.size > 0) {
            if (!shareProof) shareNotarization(shareText.toString()) else {
                val fileCacheFolder = File(cacheDir, "zips")
                fileCacheFolder.mkdir()
                generateProofZipName(fileName)
                var fileZip = File(fileCacheFolder, proofZipName)
                Timber.d("Preparing proof bundle zip: " + fileZip.absolutePath)
                try {
                    zipProof(shareUris, fileZip)
                } catch (e: IOException) {
                    Timber.e(e, "Error generating proof Zip")

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

                            var count: Int
                            val data = ByteArray(BUFFER)
                            while (fis.read(data, 0, BUFFER).also { count = it } != -1) {
                                fos.write(data, 0, count)
                            }
                            fis.close()
                            fos.close()

                        fos.close()


                    } else {
                        fileProofDownloads = File(
                            Environment.getExternalStoragePublicDirectory(
                                Environment.DIRECTORY_DOWNLOADS
                            ), fileZip.name
                        )
                        val fos = FileOutputStream(fileProofDownloads)
                        val fis = FileInputStream(fileZip)

                        var count: Int
                        val data = ByteArray(BUFFER)
                        while (fis.read(data, 0, BUFFER).also { count = it } != -1) {
                            fos.write(data, 0, count)
                        }
                        fis.close()
                        fos.close()


                    }

                    //copy IO utils
                } else {
                    Timber.d("Proof zip failed due to empty size:" + fileZip.length())
                }
            }
        }
    }

    private fun shareProofWithProgress(fileName: String, shareMedia: Boolean, shareProof: Boolean) {
        displayProgress(getString(R.string.progress_building_proof))
        shareProof(fileName, shareMedia, shareProof)
    }

    @Synchronized
    @Throws(IOException::class, PGPException::class)
    private fun shareProofInternal(fileNameOrig: String, shareMedia: Boolean, shareProof: Boolean): Boolean {

        // Get intent, action and MIME type
        val intent = intent
        val action = intent.action
        val shareUris = ArrayList<Uri?>()
        val shareItems = ArrayList<ProofableItem>()

        var fileName = fileNameOrig
        if (fileNameOrig.isEmpty())
        {
            fileName = "proofmode"
        }

        val shareText = StringBuffer()
        if (Intent.ACTION_SEND_MULTIPLE == action) {
            val mediaUris = intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM) ?: emptyList()
            val fileBatchProof: File
            val fBatchProofOut: PrintWriter
            try {
                fileBatchProof = File(cacheDir, Date().time.toString() + "batchproof.csv")
                fBatchProofOut = PrintWriter(FileWriter(fileBatchProof, true))
            } catch (ioe: IOException) {
                return false //unable to open batch proof
            }
            var successProof = 0
            var isFirstProof = true
            for (mediaUri in mediaUris) {
                shareUris.add(mediaUri)
                if (processUri(
                        null,
                        mediaUri,
                        shareUris,
                        shareItems,
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
                shareUris.add(mediaUri)
                val mediaHash = hashCache[mediaUri.toString()]
                if (!processUri(
                        mediaHash,
                        mediaUri,
                        shareUris,
                        shareItems,
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
                val fileZip = File(fileCacheFolder, "${fileName}-0x$userId-$dateString.zip")
                Timber.d("Preparing proof bundle zip: " + fileZip.absolutePath)
                try {
                    zipProof(shareUris, fileZip)
                } catch (e: IOException) {
                    Timber.e(e, "Error generating proof Zip")
                    return false
                }
                if (fileZip.length() > 0) {
                    Timber.d("Proof zip completed. Size:" + fileZip.length())
                    val encryptZip = false //we never do this
                    if (encryptZip) {
                        val fileZipEnc =
                            File(fileCacheFolder, fileZip.name + ".gpg")
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
                        "$packageName.provider",
                        fileZip
                    )
                    shareFiltered(
                        this,
                        getString(R.string.select_app),
                        shareText.toString(),
                        shareUris,
                        shareItems,
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
            if (mStorageProvider?.proofExists(hash) == true)
            {
                if (mStorageProvider?.proofIdentifierExists(hash, hash + ProofMode.PROOF_FILE_TAG) == true)
                    return hash
            }
        }
        return null
    }

    private fun generateProof(mediaUri: Uri?, proofHash: String?, allowMachineLearning: Boolean) {
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                try {
                    val isDirectCapture = false // this is from an import, and we are manually generating proof
                    
                    if ((application as ProofModeApp).useContentCredentials()) {
                        C2paUtils.addContentCredentials(this@ShareProofActivity, mediaUri, isDirectCapture, allowMachineLearning)
                    }
                    
                    ProofMode.generateProof(this@ShareProofActivity, mediaUri, proofHash)
                } catch (e: Exception) {
                    Timber.e(e, "Error generating proof")
                    null
                }
            }
            
            if (result != null) {
                displaySharePrompt()
            } else {
                showProofError()
            }
        }
    }

    private fun generateMultiProof(mediaUris: List<Uri>, allowMachineLearning: Boolean, storageProvider: StorageProvider?) {
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                var proofHash: String? = null
                for (mediaUri in mediaUris) {
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
                            if ((application as ProofModeApp).useContentCredentials()) {
                                val isDirectCapture = false // this is from an import, and we are manually generating proof
                                val fileC2PA = C2paUtils.addContentCredentials(
                                    this@ShareProofActivity,
                                    mediaUri,
                                    isDirectCapture,
                                    allowMachineLearning
                                )
                                // now add fileC2PA to proof folder
                                storageProvider?.saveStream(
                                    proofHash,
                                    fileC2PA.name,
                                    FileInputStream(fileC2PA),
                                    null
                                )
                            }
                        }
                    } catch (fe: FileNotFoundException) {
                        Timber.d("FileNotFound: %s", mediaUri)
                    }
                }
                proofHash
            }
            
            if (result != null) {
                displaySharePrompt()
            } else {
                showProofError()
            }
        }
    }

    private fun checkProof(mediaUri: Uri) {
        lifecycleScope.launch {
            val proofHash = withContext(Dispatchers.IO) {
                try {
                    proofExists(mediaUri)
                } catch (e: FileNotFoundException) {
                    Timber.w(e)
                    null
                }
            }
            
            if (proofHash != null) {
                displaySharePrompt()
            } else {
                displayGeneratePrompt()
            }
        }
    }

    private fun shareSocialAsync() {
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                try {
                    shareSocial()
                } catch (e: IOException) {
                    Timber.e(e, "error sharing social")
                    false
                } catch (e: PGPException) {
                    Timber.e(e, "error sharing social")
                    false
                }
            }
            
            if (!result) {
                Timber.d("unable to shareSocial")
                showProofError()
            }
        }
    }

    private fun shareProofAsync(fileName: String, shareMedia: Boolean, shareProof: Boolean) {
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                try {
                    shareProofAsync(fileName, shareMedia, shareProof)
                } catch (e: IOException) {
                    Timber.e(e, "error sharing proof")
                    showProofError()

                    false
                } catch (e: PGPException) {
                    Timber.e(e, "error sharing proof")
                    showProofError()

                    false
                }
            }

        }
    }

    private fun generateProof(mediaUri: Uri?, proofHash: String?) {
        generateProof(mediaUri, proofHash, mBlockAI == false)
    }

    private fun showProofError() {
        binding.apply {
            viewProof.visibility = View.GONE
            viewNoProof.visibility = View.GONE
            viewProofProgress.visibility = View.GONE
            viewProofSaved.visibility = View.GONE
            viewProofFailed.visibility = View.VISIBLE
        }
    }

    private fun showProofSaved(fileName: String?) {
        binding.apply {
            viewProof.visibility = View.GONE
            viewNoProof.visibility = View.GONE
            viewProofProgress.visibility = View.GONE
            viewProofFailed.visibility = View.GONE
            viewProofSaved.visibility = View.VISIBLE
            viewProofSavedMessage.text = """
                ${getString(R.string.share_save_downloads)}
                
                ${fileName}
                """.trimIndent()
        }
        finish()
    }

    private fun showProofUploaded(fileProof: String) {
        binding.apply {
            viewProof.visibility = View.GONE
            viewNoProof.visibility = View.GONE
            viewProofProgress.visibility = View.GONE
            viewProofFailed.visibility = View.GONE
            viewProofSaved.visibility = View.VISIBLE
        }
    }



    @Throws(IOException::class, PGPException::class)
    private fun processUri(
        mediaHash: String?,
        mediaUri: Uri?,
        shareUris: ArrayList<Uri?>,
        shareItems: ArrayList<ProofableItem>,
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
        var result: String? = null
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
            if (result == null) result =
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

        // Successful?
        if (result != null) {
            shareItems.add(ProofableItem(result, mediaUri))
        }

        return result != null
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
    ): String? {
        var hash = hash
        if (hash == null) hash = HashUtils.getSHA256FromFileContent(
            contentResolver.openInputStream(
                uriMedia!!
            )
        )
        if (hash != null && mStorageProvider?.proofExists(hash) == true) {

            mStorageProvider?.getProofSet(hash)?.let { shareUris.addAll(it) }

            val sdf = SimpleDateFormat.getDateTimeInstance()
            val fingerprint = pgpUtils.publicKeyFingerprint
            if (fileMedia != null) {
                sb.append(fileMedia.name).append(' ')
                sb.append(getString(R.string.last_modified)).append(' ')
                    .append(sdf.format(fileMedia.lastModified()))
                sb.append(' ')
            }
            sb.append(getString(R.string.has_hash)).append(' ').append(hash)
            sb.append("\n\n")
            sb.append(getString(R.string.proof_signed)).append(fingerprint)
            sb.append("\n")

            return hash

        }
        return null
    }

    @Throws(IOException::class, PGPException::class)
    private fun shareProofClassic(
        mediaUri: Uri?,
        mediaPath: String,
        shareUris: ArrayList<Uri?>,
        sb: StringBuffer,
        fBatchProofOut: PrintWriter?,
        shareMedia: Boolean
    ): String? {
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

        val sdf = SimpleDateFormat.getDateTimeInstance()
        val fingerprint = pgpUtils.publicKeyFingerprint
        if (fileMedia != null) {
            sb.append(fileMedia.name).append(' ')
            sb.append(getString(R.string.last_modified)).append(' ')
                .append(sdf.format(fileMedia?.lastModified()))
            sb.append(' ')
        }
        sb.append(getString(R.string.has_hash)).append(' ').append(hash)
        sb.append("\n\n")
        sb.append(getString(R.string.proof_signed)).append(fingerprint)
        sb.append("\n")

        return hash
    }

    private fun shareNotarization(shareText: String) {
        val shareIntent = Intent(Intent.ACTION_SEND)
        shareIntent.putExtra(Intent.EXTRA_TEXT, shareText)
        shareIntent.type = "*/*"
        startActivity(Intent.createChooser(shareIntent, getString(R.string.share_notarization)))
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
                val fileName = getFileNameFromUri(contentResolver, uri)
                Timber.d("adding to zip: $fileName")
                var isProofItem = mStorageProvider?.getProofItem(uri!!)
                if (isProofItem == null)
                    isProofItem = contentResolver.openInputStream(uri!!)
                origin = BufferedInputStream(isProofItem, BUFFER)
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
        val pubKey = ProofMode.getPublicKeyString()
        var entry: ZipEntry? = ZipEntry("pubkey.asc")
        out.putNextEntry(entry)
        out.write(pubKey.toByteArray())

        var fileCert = File(filesDir,C2PA_CERT_PATH)
        if (fileCert.exists()) {
            Timber.d("Adding C2PA certificate")
            entry = ZipEntry(C2PA_CERT_PATH)
            out.putNextEntry(entry)
            out.write(fileCert.readBytes())
        }

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

        fun shareFiltered(
            context: Context,
            shareMessage: String,
            shareText: String,
            shareUris: ArrayList<Uri?>?,
            shareItems: ArrayList<ProofableItem>?,
            shareZipUri: Uri?
        ) {
            val modeFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION
            val shareIntent = Intent()
            shareIntent.action = Intent.ACTION_SEND
            shareIntent.setDataAndType(shareZipUri, "application/zip")
            shareIntent.putExtra(Intent.EXTRA_TEXT, shareText)
            shareIntent.putExtra(Intent.EXTRA_STREAM, shareZipUri)
            shareIntent.addFlags(modeFlags)

            val sharedIntent = Intent(INTENT_ACTIVITY_ITEMS_SHARED)
            if (shareItems != null) {
                sharedIntent.putParcelableArrayListExtra(Intent.EXTRA_STREAM, shareItems)
                sharedIntent.putExtra(EXTRA_SHARE_TEXT, shareText)
                sharedIntent.putExtra(EXTRA_FILE_NAME, shareZipUri?.toString())
            }
            val pendingIntent =
                PendingIntent.getBroadcast(
                    context,
                    0,
                    sharedIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )

            val openInChooser =
                Intent.createChooser(shareIntent, shareMessage, pendingIntent.intentSender)
            openInChooser.addFlags(modeFlags)
            val resInfoList = context.packageManager.queryIntentActivities(openInChooser, 0)
            for (resolveInfo in resInfoList) {
                val packageName = resolveInfo.activityInfo.packageName
                if (shareUris != null) for (uri in shareUris) context.grantUriPermission(
                    packageName,
                    uri,
                    modeFlags
                )
                shareZipUri?.let { context.grantUriPermission(packageName, it, modeFlags) }
            }
            context.startActivity(openInChooser)
        }

        fun shareMedia(
            context: Context,
            shareText: String,
            shareUris: ArrayList<Uri?>?
        ) {

            val shareIntent = Intent()
            shareIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);

            if (shareUris?.size!! > 1)
            {
                shareIntent.action = Intent.ACTION_SEND_MULTIPLE
                shareIntent.setType("*/*")
                shareIntent.putParcelableArrayListExtra(Intent.EXTRA_STREAM, shareUris)
            }
            else
            {
                shareIntent.action = Intent.ACTION_SEND
                shareIntent.setDataAndType(shareUris[0],"image/*")
                shareIntent.putExtra(Intent.EXTRA_STREAM, shareUris[0])
            }

            //some apps don't take an image AND the text, so put it in the buffer
            val clipboard = context.getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("proofmode", shareText)
            clipboard.setPrimaryClip(clip)

            shareIntent.putExtra(Intent.EXTRA_TEXT, shareText)
         //   shareIntent.putExtra(Intent.EXTRA_TITLE, shareText)


            context.startActivity(Intent.createChooser(shareIntent,
                context.getString(R.string.share_using)));

        }


    }


}