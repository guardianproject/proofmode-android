package org.witness.proofmode

import android.Manifest
import android.content.BroadcastReceiver
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.CompoundButton
import android.widget.Toast
import androidx.appcompat.app.ActionBarDrawerToggle
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.PopupMenu
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.platform.ComposeView
import androidx.core.content.ContextCompat
import androidx.core.view.GravityCompat
import androidx.core.view.MenuItemCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.preference.PreferenceManager
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.navigation.NavigationView
import gun0912.tedimagepicker.builder.TedImagePicker
import org.witness.proofmode.ActivityConstants.EXTRA_FILE_NAME
import org.witness.proofmode.ActivityConstants.EXTRA_SHARE_TEXT
import org.witness.proofmode.ActivityConstants.INTENT_ACTIVITY_ITEMS_SHARED
import org.witness.proofmode.ProofMode.EVENT_PROOF_GENERATED
import org.witness.proofmode.ProofMode.PREF_CREDENTIALS_PRIMARY
import org.witness.proofmode.ProofMode.PREF_OPTION_AI
import org.witness.proofmode.ProofMode.PREF_OPTION_AI_DEFAULT
import org.witness.proofmode.ProofMode.PREF_OPTION_CREDENTIALS
import org.witness.proofmode.ProofMode.PREF_OPTION_CREDENTIALS_DEFAULT
import org.witness.proofmode.ProofModeConstants.PREFS_KEY_PASSPHRASE
import org.witness.proofmode.ProofModeConstants.PREFS_KEY_PASSPHRASE_DEFAULT
import org.witness.proofmode.c2pa.C2paUtils
import org.witness.proofmode.camera.CameraActivity
import org.witness.proofmode.crypto.pgp.PgpUtils
import org.witness.proofmode.databinding.ActivityMainBinding
import org.witness.proofmode.onboarding.OnboardingActivity
import org.witness.proofmode.service.MediaWatcher
import org.witness.proofmode.util.GPSTracker
import timber.log.Timber
import java.io.File
import java.io.IOException
import java.util.Date
import java.util.UUID

class MainActivity : AppCompatActivity(), NavigationView.OnNavigationItemSelectedListener,
    ActivitiesViewDelegate {
    private lateinit var mPrefs: SharedPreferences
    private lateinit var layoutOn: View
    private lateinit var layoutOff: View
    private lateinit var drawer: DrawerLayout
    private lateinit var drawerToggle: ActionBarDrawerToggle
    private lateinit var mainBinding: ActivityMainBinding
    public lateinit var fab: FloatingActionButton

    private val ACTION_OPEN_CAMERA = "org.witness.proofmode.OPEN_CAMERA"


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requiredPermissions = arrayOf(
                Manifest.permission.ACCESS_MEDIA_LOCATION,
                Manifest.permission.POST_NOTIFICATIONS,
                Manifest.permission.READ_MEDIA_IMAGES,
                Manifest.permission.READ_MEDIA_VIDEO,
                Manifest.permission.ACCESS_FINE_LOCATION
            )
        }

        mPrefs = PreferenceManager.getDefaultSharedPreferences(this)

        val isOn = mPrefs.getBoolean("doProof", false)

        if (isOn)
            (application as ProofModeApp).init(this)

        val intentFilter = IntentFilter("org.witness.proofmode.NEW_MEDIA")
        intentFilter.apply {
            addDataType("image/*")
            addDataType("video/*")
        }

        ContextCompat.registerReceiver(this,
            cameraReceiver, intentFilter,
            ContextCompat.RECEIVER_EXPORTED
        )

        ContextCompat.registerReceiver(this,
            cameraReceiver, IntentFilter(EVENT_PROOF_GENERATED),
            ContextCompat.RECEIVER_EXPORTED
        )

        ContextCompat.registerReceiver(this,
            cameraReceiver, IntentFilter(INTENT_ACTIVITY_ITEMS_SHARED),
            ContextCompat.RECEIVER_EXPORTED
        )

        initUI()

        if (intent?.action == ACTION_OPEN_CAMERA)
        {
            openCamera()
        }
        else
        {

        }



    }

    private fun initUI () {

        mainBinding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(mainBinding.root)
        val toolbar = mainBinding.toolbar
        setSupportActionBar(toolbar)
        supportActionBar?.title = getString(R.string.app_name)
        layoutOn = mainBinding.contentMain.layoutOn
        layoutOff = mainBinding.contentMain.layoutOff
        if (mPrefs.getBoolean("firsttime", true)) {
            startActivityForResult(Intent(this, OnboardingActivity::class.java), REQUEST_CODE_INTRO)
        }

        //Setup drawer
        drawer = mainBinding.drawerLayout
        drawerToggle = ActionBarDrawerToggle(
            this, drawer, toolbar, 0, 0
        )
        drawer.addDrawerListener(drawerToggle)
        drawerToggle.syncState()
        val navigationView = mainBinding.navView
        navigationView.setNavigationItemSelectedListener(this)

        /**
        var switchItem = navigationView.menu.findItem(R.id.menu_background_service);
        var switchView = MenuItemCompat.getActionView(switchItem) as CompoundButton

        switchView.isChecked =  mPrefs.getBoolean("doProof", false)

        switchView.setOnCheckedChangeListener { buttonView, isChecked ->
            setProofModeOn(isChecked)
        }**/

        val btnSettings = mainBinding.contentMain.btnSettings
        btnSettings.setOnClickListener { openSettings() }
        val btnShareProof = mainBinding.contentMain.btnShareProof
        btnShareProof.setOnClickListener {

            // Initializing the popup menu and giving the reference as current context
            val popupMenu = PopupMenu(this@MainActivity, it)

            // Inflating popup menu from popup_menu.xml file
            popupMenu.menuInflater.inflate(R.menu.menu_share_proof, popupMenu.menu)
            popupMenu.setOnMenuItemClickListener { menuItem ->
                if (menuItem.itemId == R.id.menu_photo) showMediaPicker()
                true
            }
            // Showing the popup menu
            popupMenu.show()
        }

        //updateOnOffState(false)


        fab = findViewById<FloatingActionButton>(R.id.fab)
        fab.setOnClickListener { view ->
            openCamera()
        }


        // Setup activity view
        val activityView = findViewById<ComposeView>(R.id.activityView)

        /**
        Activities.load(this)

        }**/

        activityView.setContent {
            ActivitiesView {
                itemsSelected(it)
            }
        }
    }

    private fun itemsSelected(selected: Boolean) {
        if (selected)
            fab.visibility = View.GONE
        else
            fab.visibility = View.VISIBLE
    }

    private class CameraReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                /**
                "org.witness.proofmode.NEW_MEDIA" -> {
                    val uri = intent.data
                    if (uri != null && context != null) {
                        Activities.addActivity(
                            Activity(
                                UUID.randomUUID().toString(), ActivityType.MediaCaptured(
                                    items = mutableStateListOf(
                                        ProofableItem(uri.toString(), uri)
                                    )
                                ), Date()
                            ), context
                        )

                        MediaWatcher.getInstance(context).queueMedia(intent.data, true, Date())

                    }
                }**/

                EVENT_PROOF_GENERATED -> {
                    val uri = intent.data
                    if (uri != null && context != null) {
                        //proof generated update?
                    }
                }

                INTENT_ACTIVITY_ITEMS_SHARED -> {
                    val items =
                        intent.getParcelableArrayListExtra<ProofableItem>(Intent.EXTRA_STREAM)
                            ?: ArrayList()
                    if (items.size > 0 && context != null) {
                        val fileName = intent.getStringExtra(EXTRA_FILE_NAME) ?: ""
                        val shareText = intent.getStringExtra(EXTRA_SHARE_TEXT) ?: ""
                        val activity = Activity(
                            UUID.randomUUID().toString(),
                            ActivityType.MediaShared(
                                items = items.toMutableStateList(),
                                fileName,
                                shareText = shareText
                            ),
                            Date()
                        )
                        Activities.addActivity(activity, context)
                    }
                }

                else -> {}
            }
        }
    }

    private val cameraReceiver = CameraReceiver()

    private fun showDocumentPicker() {
        val intent = Intent()
        intent.type = "*/*"
        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        intent.action = Intent.ACTION_GET_CONTENT
        startActivityForResult(
            Intent.createChooser(intent, "Choose Key and Certificate"),
            REQUEST_CODE_CHOOSE_CREDS
        )
    }

    private fun showMediaPicker() {
        TedImagePicker.with(this).imageAndVideo().showVideoDuration(true).dropDownAlbum()
            .startMultiImage {
                showShareProof(
                    it
                )
                addProofActivity(it)
            }

    }

    private fun addProofActivity(items: List<Uri>) {

        Timber.d("addProofActivity: New Proof Items: ${items.size}")

        val proofItems = ArrayList<ProofableItem>()
        for (item in items) {
            proofItems.add(ProofableItem(UUID.randomUUID().toString(), item))
        }

        val activity = Activity(
            UUID.randomUUID().toString(),
            ActivityType.MediaImported(
                items = proofItems.toMutableStateList()
            ),
            Date()
        )
        Activities.addActivity(activity, this)
    }

    private fun showShareProof(mediaList: List<Uri>) {
        val intentShare = Intent(this, ShareProofActivity::class.java)
        intentShare.action = Intent.ACTION_SEND_MULTIPLE
        val aList = ArrayList<Uri>()
        for (uri in mediaList) aList.add(uri)
        intentShare.putParcelableArrayListExtra(Intent.EXTRA_STREAM, aList)
        startActivity(intentShare)
    }

    /**
    fun toggleOnClicked(view: View?) {
        setProofModeOn(true)
    }

    fun toggleOffClicked(view: View?) {
        setProofModeOn(false)
    }**/

    private fun setProofModeOn(isOn: Boolean) {
        if (isOn) {
            if (!askForPermissions(requiredPermissions, REQUEST_CODE_REQUIRED_PERMISSIONS)) {
                mPrefs.edit().putBoolean(ProofMode.PREFS_DOPROOF, true).apply()
                //   updateOnOffState(true)
                (application as ProofModeApp).init(this)
            }
        } else {
            mPrefs.edit().putBoolean(ProofMode.PREFS_DOPROOF, false).apply()
            //  updateOnOffState(true)
            (application as ProofModeApp).cancel(this)
        }
    }



    override fun onResume() {
        super.onResume()

        if (intent?.action != ACTION_OPEN_CAMERA) {
            if (!activitiesLoaded) {
                Activities.load(this)
                activitiesLoaded = true
            }
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        // Inflate the menu; this adds items to the action bar if it is present.
        menuInflater.inflate(R.menu.menu_main, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        // Handle action bar item clicks here. The action bar will
        // automatically handle clicks on the Home/Up button, so long
        // as you specify a parent activity in AndroidManifest.xml.
        val id = item.itemId
        /**
        if (id == R.id.action_share_key) {
            shareCurrentPublicKey()
            return true
        } else **/

        if (id == R.id.action_share_photos) {
            showMediaPicker();
        }
        else if (id == R.id.menu_settings) {
            drawer.closeDrawer(GravityCompat.START)
            openSettings()
	}
        /**
        else if (id == R.id.action_import_creds) {
            showDocumentPicker();
        }**/
        return super.onOptionsItemSelected(item)
    }

    private fun pickMedia() {


        // create an instance of the
        // intent of the type image
        val i = Intent()
        i.type = "image/*"
        i.action = Intent.ACTION_GET_CONTENT

        // pass the constant to compare it
        // with the returned requestCode
        startActivityForResult(Intent.createChooser(i, "Open Picture"), 9999)
        /**
         * Matisse.from(MainActivity.this)
         * .choose(MimeType.ofAll())
         * .countable(true)
         * //   .maxSelectable(9)
         * //     .gridExpectedSize(getResources().getDimensionPixelSize(R.dimen.grid_expected_size))
         * .restrictOrientation(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED)
         * .thumbnailScale(0.85f)
         * .showPreview(false) // Default is `true`
         * .forResult(9999);
         */
    }

    /**
     * User the PermissionActivity to ask for permissions, but show no UI when calling from here.
     */
    private fun askForPermissions(permissions: Array<String>, requestCode: Int): Boolean {
        if (!PermissionActivity.hasPermissions(this, permissions)) {
            val intent = Intent(this, PermissionActivity::class.java)
            intent.putExtra(PermissionActivity.ARG_PERMISSIONS, permissions)
            startActivityForResult(intent, requestCode)
            return true
        }
        return false
    }

    private fun askForPermission(permission: String, requestCode: Int): Boolean {
        return askForPermissions(arrayOf(permission), requestCode)
    }

    private fun publishKey() {
        try {


            PgpUtils.getInstance()?.publishPublicKey()
            Toast.makeText(
                this,
                getString(R.string.publish_key_to) + PgpUtils.URL_LOOKUP_ENDPOINT,
                Toast.LENGTH_LONG
            ).show()

            //String fingerprint = mPgpUtils.getPublicKeyFingerprint();

            //Toast.makeText(this, R.string.open_public_key_page, Toast.LENGTH_LONG).show();

            //openUrl(PgpUtils.URL_LOOKUP_ENDPOINT + fingerprint);
        } catch (ioe: IOException) {
            Log.e("Proofmode", "error publishing key", ioe)
        }
    }

    private fun shareCurrentPublicKey() {
        try {


            PgpUtils.getInstance()?.publishPublicKey()
            val pubKey = PgpUtils.getInstance()?.publicKeyString
            if (pubKey != null) {
                sharePublicKey(pubKey)
            }
        } catch (ioe: IOException) {
            Log.e("Proofmode", "error publishing key", ioe)
        }
    }

    override fun sharePublicKey(pubKey: String) {
        try {
            val intent = Intent(Intent.ACTION_SEND)
            intent.type = "text/plain"
            intent.putExtra(Intent.EXTRA_TEXT, pubKey)
            startActivity(intent)

            // ACTION_SEND does not return a result, so just assume we shared ok
            val activity = Activity(
                UUID.randomUUID().toString(),
                ActivityType.PublicKeyShared(key = pubKey),
                Date()
            )
            Activities.addActivity(activity, this)
        } catch (ioe: IOException) {
            Log.e("Proofmode", "error publishing key", ioe)
        }
    }

    private fun openUrl(url: String) {
        val i = Intent(Intent.ACTION_VIEW)
        i.data = Uri.parse(url)
        startActivity(i)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_CODE_INTRO) {
            mPrefs.edit().putBoolean("firsttime", false).commit()

            // Ask for initial permissions
            if (!askForPermissions(requiredPermissions, REQUEST_CODE_REQUIRED_PERMISSIONS)) {
                // We have permission
                setProofModeOn(true)
                askForOptionals()
            }

            (application as ProofModeApp).checkAndGeneratePublicKey();

        } else if (requestCode == REQUEST_CODE_REQUIRED_PERMISSIONS) {
            // We call with REQUEST_CODE_REQUIRED_PERMISSIONS to turn ProofMode on, so set it to on if we have the permissions
            if (PermissionActivity.hasPermissions(this, requiredPermissions)) {
                setProofModeOn(true)
                askForOptionals()
            } else {
                setProofModeOn(false)
            }
        } else if (requestCode == REQUEST_CODE_OPTIONAL_PERMISSIONS) {
            if (PermissionActivity.hasPermissions(
                    this,
                    arrayOf(Manifest.permission.ACCESS_NETWORK_STATE)
                )
            ) {
                mPrefs.edit().putBoolean(ProofMode.PREF_OPTION_NETWORK, true).commit()
            } else {
                mPrefs.edit().putBoolean(ProofMode.PREF_OPTION_NETWORK, false).commit()
            }
            if (PermissionActivity.hasPermissions(
                    this,
                    arrayOf(Manifest.permission.READ_PHONE_STATE)
                )
            ) {
                mPrefs.edit().putBoolean(ProofMode.PREF_OPTION_PHONE, true).commit()
            } else {
                mPrefs.edit().putBoolean(ProofMode.PREF_OPTION_PHONE, false).commit()
            }
        } else if (requestCode == REQUEST_CODE_CHOOSE_MEDIA) {
            val intentShare = Intent(this, ShareProofActivity::class.java)
            intentShare.type = data?.type
            if (data?.data != null) {
                intentShare.action = Intent.ACTION_SEND
                intentShare.data = data.data

                Activities.addActivity(
                    Activity(
                        intentShare.data.toString(), ActivityType.MediaImported(
                            items = mutableStateListOf(
                                ProofableItem(UUID.randomUUID().toString(), data.data as Uri)
                            )
                        ), Date()
                    ), this
                )
            }
            if (data?.clipData != null) {
                intentShare.action = Intent.ACTION_SEND_MULTIPLE
                intentShare.clipData = data.clipData
            }
            startActivity(intentShare)


        }
        else if (requestCode == REQUEST_CODE_CHOOSE_CREDS) {

            if (resultCode == RESULT_OK) {
                if (null != data) {
                    if (null !=data.clipData) {

                        var importKey: Uri? = null
                        var importCert: Uri? = null

                        for (i in 0 until data.clipData!!.itemCount) {
                            val uri = data.clipData!!.getItemAt(i).uri
                            val fileName = getFileName(uri)
                            //  Log.d("Import","uri: " + uri.toString())
                            if (fileName?.endsWith(".pem") == true)
                                importKey = uri

                            if (fileName?.endsWith(".pub") == true)
                                importCert = uri


                        }

                        if (importKey != null && importCert != null)
                        C2paUtils.importCredentials(this,
                            contentResolver.openInputStream(importKey),
                            contentResolver.openInputStream(importCert))


                    } else {
                        val uri = data.data
                    }
                }
            }

        }
        else if (requestCode == REQUEST_CODE_CAMERA) {
            if (!activitiesLoaded) {
                Activities.load(this)
                activitiesLoaded = true
            }
        }
    }

    fun Context.getFileName(uri: Uri): String? = when(uri.scheme) {
        ContentResolver.SCHEME_CONTENT -> getContentFileName(uri)
        else -> uri.path?.let(::File)?.name
    }

    private fun Context.getContentFileName(uri: Uri): String? = runCatching {
        contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            cursor.moveToFirst()
            return@use cursor.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME).let(cursor::getString)
        }
    }.getOrNull()

    private fun askForOptionals() {
        if (!askForPermissions(optionalPermissions, REQUEST_CODE_OPTIONAL_PERMISSIONS)) {
            mPrefs.edit().putBoolean(ProofMode.PREF_OPTION_NETWORK, true).commit()
            // mPrefs.edit().putBoolean(ProofMode.PREF_OPTION_PHONE, true).commit()
        }
    }

    private fun refreshLocation() {
        val gpsTracker = GPSTracker(this)
        if (gpsTracker.canGetLocation()) {
            gpsTracker.location
        }
    }

    private fun openSettings() {
        val intent = Intent(this@MainActivity, SettingsActivity::class.java)
        startActivity(intent)
    }

    private fun openDataLegend() {
        val intent = Intent(this@MainActivity, DataLegendActivity::class.java)
        startActivity(intent)
    }

    private fun openDigitalSignatures() {
        val intent = Intent(this@MainActivity, DigitalSignaturesActivity::class.java)
        startActivity(intent)
    }

    private fun openVerify() {
        val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://check.proofmode.org"))
        startActivity(browserIntent)
    }

    override fun onNavigationItemSelected(menuItem: MenuItem): Boolean {
        when (menuItem.itemId) {
            R.id.menu_how_it_works -> {
                drawer.closeDrawer(GravityCompat.START)
                val intent = Intent(this, OnboardingActivity::class.java)
                intent.putExtra(OnboardingActivity.ARG_ONLY_TUTORIAL, true)
                startActivityForResult(intent, REQUEST_CODE_INTRO)
                return true
            }

            R.id.menu_settings -> {
                drawer.closeDrawer(GravityCompat.START)
                openSettings()
                return true
            }

            R.id.menu_datalegend -> {
                drawer.closeDrawer(GravityCompat.START)
                openDataLegend()
                return true
            }

            R.id.menu_digital_signatures -> {
                drawer.closeDrawer(GravityCompat.START)
                openDigitalSignatures()
                return true
            }

            R.id.menu_verify -> {
                drawer.closeDrawer(GravityCompat.START)
                openVerify()
                return true
            }
        }
        return false
    }

    fun startCamera(view: View?) {
        val intentCam = Intent(this, CameraActivity::class.java)

        val useCredentials = mPrefs.getBoolean(
            ProofMode.PREF_OPTION_CREDENTIALS,
            ProofMode.PREF_OPTION_CREDENTIALS_DEFAULT
        );

        intentCam.putExtra(PREF_OPTION_CREDENTIALS, useCredentials);
        intentCam.putExtra(PREF_OPTION_AI, mPrefs.getBoolean(PREF_OPTION_AI, PREF_OPTION_AI_DEFAULT));

        if (useCredentials)
            (application as ProofModeApp).initContentCredentials()

        startActivityForResult(intentCam,REQUEST_CODE_CAMERA)
    }



    companion object {
        private const val REQUEST_CODE_INTRO = 9999
        private const val REQUEST_CODE_REQUIRED_PERMISSIONS = 9998
        private const val REQUEST_CODE_OPTIONAL_PERMISSIONS = 9997
        private const val REQUEST_CODE_CHOOSE_MEDIA = 9996
        private const val REQUEST_CODE_CAMERA = 9995
        private const val REQUEST_CODE_CHOOSE_CREDS = 9994

        /**
         * The permissions needed for "base" ProofMode to work, without extra options.
         */
        private var requiredPermissions = arrayOf(
            Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.ACCESS_FINE_LOCATION
        )
        private val optionalPermissions = arrayOf(
            Manifest.permission.ACCESS_NETWORK_STATE,
        )

        var activitiesLoaded = false

    }

    override fun openCamera() {
        this.startCamera(findViewById<ComposeView>(R.id.activityView))
    }

    override fun shareItems(media: List<ProofableItem>, fileName: String?, shareText: String?) {
        // Check if we still have the original to share.
        if (fileName != null) {
            val uri = Uri.parse(fileName)
            if (uri != null && contentResolver.getType(uri) != null) {


                // Use existing .zip
                val aList = ArrayList<ProofableItem>()
                for (item in media) aList.add(item)
                ShareProofActivity.shareFiltered(
                    this,
                    getString(R.string.select_app),
                    shareText ?: getString(R.string.default_share_text),
                    null,
                    aList,
                    uri
                )


                return
            }
        }
        this.showShareProof(mediaList = media.map { it.uri }.filterNotNull())
    }

    override fun clearItems(activity: Activity) {

        Activities.clearActivity(activity.id, this)

        if (activity.type is ActivityType.MediaCaptured)
            for (pi in (activity.type as ActivityType.MediaCaptured).items)
                Activities.clearActivity(pi.id, this)

        if (activity.type is ActivityType.MediaImported)
            for (pi in (activity.type as ActivityType.MediaImported).items)
                Activities.clearActivity(pi.id, this)

        Activities.load(this)
        val activityView = findViewById<ComposeView>(R.id.activityView)
        activityView.setContent {
            ActivitiesView {
                itemsSelected(it)
            }
        }
    }
}
