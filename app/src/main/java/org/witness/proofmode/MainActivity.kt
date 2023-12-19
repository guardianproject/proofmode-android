package org.witness.proofmode

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.net.Uri
import android.os.Build
import android.os.Bundle
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
import org.witness.proofmode.camera.CameraActivity
import org.witness.proofmode.camera.c2pa.C2paUtils
import org.witness.proofmode.crypto.pgp.PgpUtils
import org.witness.proofmode.databinding.ActivityMainBinding
import org.witness.proofmode.onboarding.OnboardingActivity
import org.witness.proofmode.service.MediaWatcher
import org.witness.proofmode.util.GPSTracker
import java.io.IOException
import java.util.Date
import java.util.UUID

class MainActivity : AppCompatActivity(), NavigationView.OnNavigationItemSelectedListener,
    ActivitiesViewDelegate {
    private lateinit var mPrefs: SharedPreferences
    private var mPgpUtils: PgpUtils? = null
    private lateinit var layoutOn: View
    private lateinit var layoutOff: View
    private lateinit var drawer: DrawerLayout
    private lateinit var drawerToggle: ActionBarDrawerToggle
    private lateinit var mainBinding: ActivityMainBinding
    public lateinit var fab: FloatingActionButton

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requiredPermissions = arrayOf(
                Manifest.permission.ACCESS_MEDIA_LOCATION,
                Manifest.permission.POST_NOTIFICATIONS,
                Manifest.permission.READ_MEDIA_IMAGES,
                Manifest.permission.READ_MEDIA_VIDEO
            )
        }

        mainBinding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(mainBinding.root)
        val toolbar = mainBinding.toolbar
        setSupportActionBar(toolbar)
        supportActionBar?.title = ""
        mPrefs = PreferenceManager.getDefaultSharedPreferences(this)
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

        var switchItem = navigationView.menu.findItem(R.id.menu_background_service);
        var switchView = MenuItemCompat.getActionView(switchItem) as CompoundButton
        val isOn = mPrefs.getBoolean("doProof", false)

        switchView.isChecked = isOn

        switchView.setOnCheckedChangeListener { buttonView, isChecked ->
            setProofModeOn(isChecked)
        }

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
                else if (menuItem.itemId == R.id.menu_files) showDocumentPicker()
                true
            }
            // Showing the popup menu
            popupMenu.show()
        }
        //updateOnOffState(false)

        if (isOn)
            (application as ProofModeApp).init(this)

        // Setup activity view
        val activityView = findViewById<ComposeView>(R.id.activityView)
        activityView.setContent {
            ActivitiesView {
                itemsSelected(it)
            }
        }

        val intentFilter = IntentFilter("org.witness.proofmode.NEW_MEDIA")
        intentFilter.apply {
            addDataType("image/*")
            addDataType("video/*")
        }

        LocalBroadcastManager.getInstance(applicationContext)
            .registerReceiver(cameraReceiver, intentFilter)
        LocalBroadcastManager.getInstance(applicationContext)
            .registerReceiver(cameraReceiver, IntentFilter(EVENT_PROOF_GENERATED))

        registerReceiver(cameraReceiver, IntentFilter(INTENT_ACTIVITY_ITEMS_SHARED))

        Activities.load(this)

        fab = findViewById<FloatingActionButton>(R.id.fab)
        fab.setOnClickListener { view ->
            openCamera()
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

    /**
    private fun startService() {
        val intentService = Intent(this, ProofService::class.java)
        intentService.action = ProofService.ACTION_START

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intentService)
        } else {
            startService(intentService)
        }
    }**/

    private fun showDocumentPicker() {
        val intent = Intent()
        intent.type = "*/*"
        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        intent.action = Intent.ACTION_GET_CONTENT
        startActivityForResult(
            Intent.createChooser(intent, getString(R.string.share_proof_action)),
            REQUEST_CODE_CHOOSE_MEDIA
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

    fun toggleOnClicked(view: View?) {
        setProofModeOn(true)
    }

    fun toggleOffClicked(view: View?) {
        setProofModeOn(false)
    }

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

    /**
    private fun updateOnOffState(animate: Boolean) {
    val isOn = mPrefs.getBoolean("doProof", false)
    if (animate) {
    layoutOn.animate().alpha(if (isOn) 1.0f else 0.0f).setDuration(300)
    .setListener(object : Animator.AnimatorListener {
    override fun onAnimationStart(animation: Animator) {
    if (isOn) {
    layoutOn.visibility = View.VISIBLE
    }
    }

    override fun onAnimationEnd(animation: Animator) {
    if (!isOn) {
    layoutOn.visibility = View.GONE
    }
    }

    override fun onAnimationCancel(animation: Animator) {}
    override fun onAnimationRepeat(animation: Animator) {}
    }).start()
    layoutOff.animate().alpha(if (isOn) 0.0f else 1.0f).setDuration(300)
    .setListener(object : Animator.AnimatorListener {
    override fun onAnimationStart(animation: Animator) {
    if (!isOn) {
    layoutOff.visibility = View.VISIBLE
    }
    }

    override fun onAnimationEnd(animation: Animator) {
    if (isOn) {
    layoutOff.visibility = View.GONE
    }
    }

    override fun onAnimationCancel(animation: Animator) {}
    override fun onAnimationRepeat(animation: Animator) {}
    }).start()
    } else {
    layoutOn.alpha = if (isOn) 1.0f else 0.0f
    layoutOn.visibility = if (isOn) View.VISIBLE else View.GONE
    layoutOff.alpha = if (isOn) 0.0f else 1.0f
    layoutOff.visibility = if (isOn) View.GONE else View.VISIBLE
    }

    }**/

    override fun onResume() {
        super.onResume()
        //   updateOnOffState(false)
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
        if (id == R.id.action_share_key) {
            shareCurrentPublicKey()
            return true
        } else if (id == R.id.action_share_photos) {
            showMediaPicker();
        }
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
            if (mPgpUtils == null) mPgpUtils = PgpUtils.getInstance(
                this,
                mPrefs.getString(PREFS_KEY_PASSPHRASE, PREFS_KEY_PASSPHRASE_DEFAULT)
            )
            mPgpUtils?.publishPublicKey()
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
            if (mPgpUtils == null) mPgpUtils = PgpUtils.getInstance(
                this,
                mPrefs.getString(PREFS_KEY_PASSPHRASE, PREFS_KEY_PASSPHRASE_DEFAULT)
            )
            mPgpUtils?.publishPublicKey()
            val pubKey = mPgpUtils?.publicKeyString
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
    }

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

        }
        return false
    }

    fun startCamera(view: View?) {
        val intentCam = Intent(this, CameraActivity::class.java)

        initPgpKey()

        var useCredentials = mPrefs.getBoolean(PREF_OPTION_CREDENTIALS, PREF_OPTION_CREDENTIALS_DEFAULT);

        intentCam.putExtra(PREF_OPTION_CREDENTIALS, useCredentials);
        intentCam.putExtra(PREF_OPTION_AI, mPrefs.getBoolean(PREF_OPTION_AI, PREF_OPTION_AI_DEFAULT));

        if (useCredentials)
            initContentCredentials()


        startActivity(intentCam)
    }

    fun initContentCredentials () {
        val email = mPrefs.getString(PREF_CREDENTIALS_PRIMARY,"");
        var display : String? = null
        var key : String? = "0x" + mPgpUtils?.publicKeyFingerprint
        var uri : String? = null

        if (email?.isNotEmpty() == true)
        {
            display = "${email.replace("@"," at ")}"
        }

        uri =
            "https://keys.openpgp.org/search?q=" + mPgpUtils?.publicKeyFingerprint

        C2paUtils.setC2PAIdentity(display, uri, email, key)
        if (email != null && key != null) {
                C2paUtils.initCredentials(this, email, key)
        }
    }

    fun initPgpKey () {
        if (mPgpUtils == null) {
            mPgpUtils = PgpUtils.getInstance(
                this,
                mPrefs.getString(PREFS_KEY_PASSPHRASE, PREFS_KEY_PASSPHRASE_DEFAULT)
            )

            initContentCredentials()

        }
    }

    companion object {
        private const val REQUEST_CODE_INTRO = 9999
        private const val REQUEST_CODE_REQUIRED_PERMISSIONS = 9998
        private const val REQUEST_CODE_OPTIONAL_PERMISSIONS = 9997
        private const val REQUEST_CODE_CHOOSE_MEDIA = 9996

        /**
         * The permissions needed for "base" ProofMode to work, without extra options.
         */
        private var requiredPermissions = arrayOf(
            Manifest.permission.READ_EXTERNAL_STORAGE,
        )
        private val optionalPermissions = arrayOf(
            Manifest.permission.ACCESS_NETWORK_STATE,
        )
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