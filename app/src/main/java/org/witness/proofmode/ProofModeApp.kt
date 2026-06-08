package org.witness.proofmode

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.preference.PreferenceManager
import android.util.Log
import android.widget.Toast
import androidx.work.Configuration
import com.aheaditec.talsec_security.security.api.SuspiciousAppInfo
import com.aheaditec.talsec_security.security.api.Talsec
import com.aheaditec.talsec_security.security.api.TalsecConfig
import com.aheaditec.talsec_security.security.api.TalsecMode
import com.aheaditec.talsec_security.security.api.ThreatListener
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import org.acra.config.dialog
import org.acra.config.mailSender
import org.acra.data.StringFormat
import org.acra.ktx.initAcra
import org.bouncycastle.openpgp.PGPException
import org.witness.proofmode.ProofModeApp.Companion.EXPECTED_PACKAGE_NAME
import org.witness.proofmode.ProofModeApp.Companion.EXPECTED_SIGNING_CERTIFICATE_HASH_BASE64
import org.witness.proofmode.ProofModeApp.Companion.IS_PROD
import org.witness.proofmode.c2pa.C2PAManager
import org.witness.proofmode.c2pa.DeviceIntegritySupport
import org.witness.proofmode.c2pa.PreferencesManager
import org.witness.proofmode.c2pa.proofsign.ProofSignClient
import org.witness.proofmode.c2pa.proofsign.Result
import org.witness.proofmode.c2pa.proofsign.SignerException
import org.witness.proofmode.crypto.pgp.PassphraseKeystore
import org.witness.proofmode.crypto.pgp.PgpUtils
import org.witness.proofmode.library.BuildConfig
import org.witness.proofmode.notaries.NostrNotarizationProvider
import org.witness.proofmode.notaries.OpenTimestampsNotarizationProvider
import org.witness.proofmode.notarization.NotarizationProvider
import org.witness.proofmode.storage.StorageProviderManager
import timber.log.Timber
import java.io.IOException
import java.util.concurrent.Executors
import kotlin.system.exitProcess
import info.guardianproject.durindoor.Native

private var mPgpUtils: PgpUtils? = null
private lateinit var mPrefs: SharedPreferences

/**
 * Created by n8fr8 on 10/10/16.
 */
class ProofModeApp : Application(), Configuration.Provider {
    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setMinimumLoggingLevel(android.util.Log.INFO)
            .build()

    override fun onCreate() {
        super.onCreate()

        val expectedPackageName =
            if (BuildConfig.DEBUG) "$EXPECTED_PACKAGE_NAME.debug" else EXPECTED_PACKAGE_NAME
        val isProd = IS_PROD && !BuildConfig.DEBUG
        val killOnBypass = KILL_ON_BYPASS && !BuildConfig.DEBUG

        val config = TalsecConfig.Builder(
            expectedPackageName,
            EXPECTED_SIGNING_CERTIFICATE_HASH_BASE64)
            .watcherMail(WATCHER_MAIL)
         //   .supportedAlternativeStores(SUPPORTED_ALTERNATIVE_STORES)
            .prod(isProd)
            .killOnBypass(killOnBypass)
            .build()

        val threatDetectedListener = object : ThreatListener.ThreatDetected() {

            override fun onRootDetected() {
                println("onRootDetected")
                if (!BuildConfig.DEBUG) {
                    exitProcess(0)
                }
            }

            override fun onDebuggerDetected() {
                println("onDebuggerDetected")

                if (!BuildConfig.DEBUG) {
                    showWarning("No debugging allowed");
                    exitProcess(0)
                }
            }

            override fun onEmulatorDetected() {
                println("onEmulatorDetected")
                if (!BuildConfig.DEBUG) {
                    showWarning("Emulators are not supported");
                    exitProcess(0)

                }

            }

            override fun onTamperDetected() {
                println("onTamperDetected")

                if (!BuildConfig.DEBUG) {
                    showWarning("Tampering detected");
                    exitProcess(0)
                }

            }

            override fun onUntrustedInstallationSourceDetected() {
                println("onUntrustedInstallationSourceDetected")

                if (!BuildConfig.DEBUG) {
                  //  showWarning("Untrusted installation source detected");
                //    exitProcess(0)
                }
            }

            override fun onHookDetected() {
                println("onHookDetected")
                if (!BuildConfig.DEBUG) {
                    showWarning("Ye olde Captain Hook, eh?!");
                    exitProcess(0)
                }
            }

            override fun onDeviceBindingDetected() {
                println("onDeviceBindingDetected")
            }

            override fun onObfuscationIssuesDetected() {
                if (!BuildConfig.DEBUG) {
                    println("onObfuscationIssueDetected")
                    showWarning("Obfuscation issue detected");
                    exitProcess(0)
                }
            }

            override fun onScreenshotDetected() {
                println("onScreenshotDetected")
            }

            override fun onScreenRecordingDetected() {
                println("onScreenRecordingDetected")
            }

            override fun onMultiInstanceDetected() {
                println("onMultiInstanceDetected")

                if (!BuildConfig.DEBUG) {
                   // showWarning("Multiple instances detected");
                    //exitProcess(0)
                }
            }

            override fun onUnsecureWifiDetected() {
                println("onUnsecureWifiDetected")
            }

            override fun onTimeSpoofingDetected() {
                println("onTimeSpoofingDetected")
                if (!BuildConfig.DEBUG) {
                    showWarning("Timespoofing detected");
                    exitProcess(0)
                }
            }

            override fun onLocationSpoofingDetected() {
                println("onLocationSpoofingDetected")
                if (!BuildConfig.DEBUG) {
                    showWarning("Location spoofing detected");
                //    exitProcess(0)
                }
            }

            override fun onAutomationDetected() {
                println("onAutomationDetected")
                if (!BuildConfig.DEBUG) {
                    showWarning("Automation detected");
                    exitProcess(0)
                }
            }

            override fun onMalwareDetected(suspiciousApps: List<SuspiciousAppInfo>) {
                println("onMalwareDetected")
                if (!BuildConfig.DEBUG) {
                    showWarning("Malware detected");
                    exitProcess(0)
                }
            }
        }

        ThreatListener(threatDetectedListener).registerListener(this)
        Talsec.start(this, config, TalsecMode.BACKGROUND)

        //aggressively stop people trying to instrument the app
        var dIntMan = DeviceIntegritySupport()
        if (!BuildConfig.DEBUG && dIntMan.isEnvironmentCompromised())
            exitProcess(0)

        //and do not allow developer mode
        if(!BuildConfig.DEBUG && dIntMan.isDeveloperAttackSurfaceOpen(this)) {
          //  Toast.makeText(this, getString(R.string.security_warning_usb),Toast.LENGTH_LONG).show()
            //exitProcess(0)
        }

        // Seed signing preferences from XML defaults so the configured server URL
        // and other signing settings exist before any signing path reads them.
        androidx.preference.PreferenceManager.setDefaultValues(this, R.xml.signing_preferences, false)

        mPrefs = androidx.preference.PreferenceManager.getDefaultSharedPreferences(this)

        // Reconcile the keystore-wrapped passphrase with the on-disk keyring
        // *before* anything else can touch PassphraseKeystore. If init(this)
        // runs first it ends up calling MediaWatcher.getInstance() (when
        // PREFS_DOPROOF is true), which would call getOrCreatePassphrase()
        // and auto-generate a passphrase that doesn't match the existing
        // legacy keyring — producing PGP checksum-mismatch crashes on sign.
        // This is fast: no 4096-bit RSA generation here, only file checks,
        // a PBE test-decrypt, and (on migration) an in-memory keyring rewrite.
        provisionPassphrase()

        init(this)

        //add google safetynet and opentimestamps
        addDefaultNotarizationProviders()

        GlobalScope.launch(Dispatchers.IO) {
            // Slow path (4096-bit RSA × 2 on a fresh install) goes on IO.
            // By now provisionPassphrase has guaranteed the wrapper holds
            // whatever passphrase the keyring will need.
            initPgpKey()
        }

        StorageProviderManager.getInstance().initializeStorageProviders(this)

        val isNative = runCatching {

            val nativeClass = Class.forName("info.guardianproject.durindoor.Native")
            val instance = nativeClass.getField("INSTANCE").get(null)
            val loaded = nativeClass.getMethod("getLoaded").invoke(instance) as Boolean
            if (!loaded) {
                "DD library refused to load."
            } else {
                runCatching { nativeClass.getMethod("nativePing").invoke(null) as String }
                    .getOrElse { "DD native methods unregistered" }
            }

        }.getOrElse { "DD library not available." }

        Timber.d("DD=$isNative")


    }

    fun showWarning (message: String) {
        Toast.makeText(applicationContext,message,Toast.LENGTH_LONG).show();

    }

    //ensure this device has been updated with security patches within 90 days
    fun checkC2PAConformance () : Boolean {

        val c2paMan = C2PAManager(this, PreferencesManager(this))
        val certChain = c2paMan.getDeviceAttestationCertChain("test")
        val conformant = c2paMan.checkOSSecurityPatchDate(90, certChain)

        if (!conformant)
        {
		//set to local signing only
		        mPrefs?.edit()?.putBoolean(
                        ProofMode.PREF_OPTION_REMOTE_SIGNING,
                        false
                    )?.commit()

        }

        return conformant
    }

    /**
     * Synchronous reconciliation of the on-disk keyring with the keystore-
     * wrapped passphrase store. Idempotent and self-healing: detects three
     * states and converges all of them to "wrapper holds a passphrase that
     * actually decrypts the keyring."
     *
     *  1. No keyring on disk: persist a fresh passphrase so [initPgpKey]
     *     creates the keyring under it.
     *  2. Keyring exists, wrapper empty: legacy install — figure out which
     *     passphrase the keyring is actually encrypted with (try the legacy
     *     "password" default and the historical pgpkp pref), migrate to a
     *     fresh wrapped passphrase, scrub the legacy plaintext pref.
     *  3. Keyring exists, wrapper holds something: verify the wrapped
     *     passphrase decrypts the keyring. If not (e.g. an earlier buggy
     *     build of this code stored a fresh passphrase before migration ran),
     *     fall through to the legacy-recovery path.
     *
     * Must be called before any other component touches PassphraseKeystore
     * (i.e. before init(this) → MediaWatcher.getInstance), otherwise the
     * first reader auto-generates a passphrase that doesn't match the
     * keyring and provisioning loses the race.
     */
    private fun provisionPassphrase() {
        try {
            val keyringExists = PgpUtils.keyRingExists(this)
            if (!keyringExists) {
                // Fresh install. Persist now so MediaWatcher / initPgpKey
                // both see the same passphrase, and initPgpKey creates the
                // keyring under it.
                PassphraseKeystore.getOrCreatePassphrase(this)
                return
            }

            // Keyring exists. Figure out which passphrase actually decrypts it.
            val stored = if (PassphraseKeystore.hasPassphrase(this))
                PassphraseKeystore.getOrCreatePassphrase(this) else null
            if (stored != null && PgpUtils.canDecryptKeyring(this, stored)) {
                // Healthy steady state.
                return
            }

            // Either no wrapper yet, or wrapper holds the wrong passphrase
            // (broken earlier build). Probe the legacy candidates.
            val legacyDefault = ProofModeConstants.PREFS_KEY_PASSPHRASE_DEFAULT
            val legacyPref = mPrefs.getString(ProofModeConstants.PREFS_KEY_PASSPHRASE, null)

            val workingLegacy = sequenceOf(legacyDefault, legacyPref)
                .filterNotNull()
                .firstOrNull { PgpUtils.canDecryptKeyring(this, it) }

            if (workingLegacy == null) {
                Timber.e("On-disk PGP keyring decrypts with no known passphrase; cannot auto-recover")
                // Don't leave the wrapper in a state that pretends a working
                // passphrase exists — sign attempts should fail loudly rather
                // than appear to succeed with a non-verifiable signature.
                return
            }

            val fresh = PassphraseKeystore.generatePassphrase()
            try {
                PgpUtils.init(this, workingLegacy)
                mPgpUtils = PgpUtils.getInstance()
                val migrated = mPgpUtils?.changePassphrase(this, workingLegacy, fresh) == true
                if (migrated) {
                    PassphraseKeystore.storePassphrase(this, fresh)
                    mPrefs.edit().remove(ProofModeConstants.PREFS_KEY_PASSPHRASE).apply()
                    Timber.i("Migrated PGP keyring to keystore-wrapped passphrase")
                } else {
                    // Rewrite failed — pin the wrapper to the legacy passphrase
                    // that actually decrypts the keyring so signs work. Better
                    // a weak passphrase that signs than a strong one that crashes.
                    PassphraseKeystore.storePassphrase(this, workingLegacy)
                    Timber.w("PGP keyring rewrite failed; pinning wrapper to legacy passphrase")
                }
            } catch (e: Exception) {
                Timber.e(e, "PGP migration failed; pinning wrapper to legacy passphrase")
                PassphraseKeystore.storePassphrase(this, workingLegacy)
            }
        } catch (e: Exception) {
            // Defensive: provisionPassphrase runs in onCreate and must not
            // bring the app down. A failure here means downstream signs will
            // surface their own error, which is recoverable; an exception
            // escaping onCreate is not.
            Timber.e(e, "provisionPassphrase failed")
        }
    }

    fun initPgpKey () {
        if (mPgpUtils != null) return

        // Apply any stored email-as-key-id before the keyring is generated.
        // (Previously this lived in the dead else-branch of checkAndGeneratePublicKey;
        // moving it here is the only way it can actually influence keyring creation.)
        val accountEmail = mPrefs.getString(ProofMode.PREF_CREDENTIALS_PRIMARY, "")
        if (!accountEmail.isNullOrEmpty()) {
            PgpUtils.setKeyid(accountEmail)
        }

        // provisionPassphrase has already reconciled the wrapper with the
        // on-disk keyring, so we just read whichever passphrase it parked there.
        val passphrase = PassphraseKeystore.getOrCreatePassphrase(this)

        try {
            PgpUtils.init(this, passphrase)
            mPgpUtils = PgpUtils.getInstance()
        } catch (e: Exception) {
            Timber.e(e, "PgpUtils init failed")
        }
    }

    public fun useContentCredentials () : Boolean {

        var useCredentials = mPrefs.getBoolean(
            ProofMode.PREF_OPTION_CREDENTIALS,
            ProofMode.PREF_OPTION_CREDENTIALS_DEFAULT
        );

        return useCredentials

    }

    fun initContentCredentials () {

        val conformant = checkC2PAConformance()

        if (conformant)
            initProofSignClient()

    }

    fun initProofSignClient () {

        // Remote signing requires Play Integrity. No-Play devices never contact
        // the ProofSign server (they sign locally), so skip eager registration.
        if (!ProofSignClient.isPlayIntegrityAvailable(applicationContext)) {
            Timber.d("ProofSign: Play Integrity unavailable; skipping registration (local signing only)")

            //set to local signing only
            mPrefs?.edit()?.putBoolean(
                ProofMode.PREF_OPTION_REMOTE_SIGNING,
                false
            )?.commit()

            return
        }

        val prefs = androidx.preference.PreferenceManager.getDefaultSharedPreferences(this)
        var serverUrl = prefs.getString(
            ProofMode.PREF_OPTION_PROOFSIGN_SERVER,
            "",
        ).orEmpty().trim().trimEnd('/')
        
        if (serverUrl.isEmpty())
            serverUrl = BuildConfig.SIGNING_SERVER

        val proofSignClient = ProofSignClient(
            context = applicationContext,
            serverUrl = serverUrl,
            cloudProjectNumber = BuildConfig.CLOUD_INTEGRITY_PROJECT_NUMBER,
        )

        if (!proofSignClient.isDeviceRegistered()) {

            if (DeviceIntegritySupport().detectThreats(this))
            {
                Timber.w("Cannot reverify due to suspicious device state (USB, Developer mode, Frida)")
            }
            else {

                Timber.i("Need to register device with ProofSign server")
                proofSignClient.verifyDevice () { result ->
                    when (result) {
                        is Result.Success -> Timber.i("ProofSign: VERIFIED (${result.data.verdict})")
                        is Result.Failure -> Timber.d("ProofSign: FAILURE (${result.error})")
                    }
                }
            }
        } else {
            Timber.d("ProofSign registration: valid")
        }
    }


    fun checkAndGeneratePublicKey() {
        Executors.newSingleThreadExecutor().execute {
            try {
                // initPgpKey is idempotent and the single owner of keyring
                // creation, legacy migration, and passphrase provisioning.
                initPgpKey()
                mPgpUtils?.publicKeyFingerprint
            } catch (e: PGPException) {
                Timber.e(e, "error getting public key")
                showToastMessage(getString(R.string.pub_key_gen_error))
            } catch (e: IOException) {
                Timber.e(e, "error getting public key")
                showToastMessage(getString(R.string.pub_key_gen_error))
            }
        }
    }

    private fun showToastMessage(message: String) {
        val handler = Handler(Looper.getMainLooper())
        handler.post {
            //UI Thread work here

            Toast.makeText(applicationContext, message, Toast.LENGTH_LONG).show()
        }
    }

    fun init(context: Context) {

        if (BuildConfig.DEBUG)
            Timber.plant(Timber.DebugTree())

        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        if (prefs.getBoolean(ProofMode.PREFS_DOPROOF, false)) {

            ProofMode.initBackgroundService(this)


        }

        ProofMode.startLocationListener(this)


        initContentCredentials()
    }

    override fun attachBaseContext(base:Context) {
        super.attachBaseContext(base)


        initAcra {
            //core configuration:
            buildConfigClass = BuildConfig::class.java
            reportFormat = StringFormat.KEY_VALUE_LIST
            //each plugin you chose above can be configured in a block like this:
            dialog {
                enabled = true
                text = "Would you like to share a crash report?"
                title = "Crash Report"
                positiveButtonText = "Yes"
                negativeButtonText = "No"
            }
            mailSender {
                //required
                mailTo = "support@guardianproject.info"
                //defaults to true
                reportAsFile = true
                //defaults to ACRA-report.stacktrace
                reportFileName = "Crash.txt"
                //defaults to "<applicationId> Crash Report"
                subject = "ProofMode Crash Report"
                //defaults to empty
                body = "Here it is!"
            }
        }

        /**
         *
         *             class MailSenderConfiguration(
         *                 val enabled: Boolean = true,
         *                 val mailTo: String,
         *                 val reportAsFile: Boolean = true,
         *                 val reportFileName: String = EmailIntentSender.DEFAULT_REPORT_FILENAME,
         *                 val subject: String? = null,
         *                 val body: String? = null
         *             ) : Configuration
         *
         *             class DialogConfiguration(
         *     val enabled: Boolean = true,
         *     val reportDialogClass: Class<out Activity> = CrashReportDialog::class.java,
         *     val positiveButtonText: String? = null,
         *     val negativeButtonText: String? = null,
         *     val commentPrompt: String? = null,
         *     val emailPrompt: String? = null,
         *     @DrawableRes val resIcon: Int? = android.R.drawable.ic_dialog_alert,
         *     val text: String? = null,
         *     val title: String? = null,
         *     @StyleRes val resTheme: Int? = null
         * ) : Configuration
         */
    }

    fun cancel(context: Context) {
      //  val intentService = Intent(context, ProofService::class.java)
       // context.stopService(intentService)
        ProofMode.stopBackgroundService(this)
    }

    private fun addDefaultNotarizationProviders() {

        try {
            //this may not be included in the current build
            Class.forName("com.eternitywall.ots.OpenTimestamps")
            val nProvider: NotarizationProvider = OpenTimestampsNotarizationProvider(this)
            ProofMode.addNotarizationProvider(this, nProvider)
        } catch (e: ClassNotFoundException) {
            //class not available
        }

        try {
            //this may not be included in the current build
            Class.forName("rust.nostr.sdk.Keys")
            val nProvider: NotarizationProvider = NostrNotarizationProvider(this)
            ProofMode.addNotarizationProvider(this, nProvider)
        } catch (e: ClassNotFoundException) {
            //class not available
        }


    }


    private companion object {
        init {
            System.loadLibrary("durindoor")

         //   DeviceIntegritySupport.ensureNativeLoaded()
        }

        public const val EXPECTED_PACKAGE_NAME = "org.witness.proofmode" // Don't use Context.getPackageName!
        private val EXPECTED_SIGNING_CERTIFICATE_HASH_BASE64 = arrayOf(
            "8AaiBIHHGmkN4C44WrDJ+krBJFJA9oECaCcDugZWhno="
        ) // Replace with your release (!) signing certificate hashes
        private const val WATCHER_MAIL = "nathan@proofmode.org"
        private val SUPPORTED_ALTERNATIVE_STORES = arrayOf(
            "com.sec.android.app.samsungapps"
            // add other stores, such as the Samsung Galaxy Store
        )
        private const val IS_PROD = true
        private const val KILL_ON_BYPASS = true
    }
}
