package org.witness.proofmode

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.preference.PreferenceManager
import android.widget.Toast
import androidx.multidex.MultiDexApplication
import org.acra.ACRA.init
import org.acra.config.CoreConfigurationBuilder
import org.acra.config.MailSenderConfigurationBuilder
import org.acra.config.dialog
import org.acra.config.mailSender
import org.acra.data.StringFormat
import org.acra.ktx.initAcra
import org.bouncycastle.openpgp.PGPException
import org.proofmode.c2pa.C2paJNI
import org.witness.proofmode.ProofModeConstants.PREFS_KEY_PASSPHRASE
import org.witness.proofmode.ProofModeConstants.PREFS_KEY_PASSPHRASE_DEFAULT
import org.witness.proofmode.crypto.pgp.PgpUtils
import org.witness.proofmode.notaries.GoogleSafetyNetNotarizationProvider
import org.witness.proofmode.notaries.OpenTimestampsNotarizationProvider
import org.witness.proofmode.notaries.SafetyNetCheck
import org.witness.proofmode.notarization.NotarizationProvider
import timber.log.Timber
import java.io.IOException
import java.util.concurrent.Executors
import kotlin.random.Random


/**
 * Created by n8fr8 on 10/10/16.
 */
class ProofModeApp : MultiDexApplication() {
    override fun onCreate() {
        super.onCreate()
        init(this, false)
    }

    fun checkAndGeneratePublicKey() {
        Executors.newSingleThreadExecutor().execute {

            //Background work here
            var pubKey: String? = null
            try {
                val prefs = PreferenceManager.getDefaultSharedPreferences(this)

                if (PgpUtils.keyRingExists(this)) {
                    pubKey = PgpUtils.getInstance(
                        applicationContext, prefs.getString(
                            PREFS_KEY_PASSPHRASE,
                            PREFS_KEY_PASSPHRASE_DEFAULT
                        )
                    ).publicKeyFingerprint
                }
                else
                {
                    var newPassPhrase = getRandPassword(12)
                    prefs.edit().putString(PREFS_KEY_PASSPHRASE,newPassPhrase).commit()
                    pubKey = PgpUtils.getInstance(
                        applicationContext, newPassPhrase
                    ).publicKeyFingerprint

                }

            } catch (e: PGPException) {
                Timber.e(e, "error getting public key")
                showToastMessage(getString(R.string.pub_key_gen_error))
            } catch (e: IOException) {
                Timber.e(e, "error getting public key")
                showToastMessage(getString(R.string.pub_key_gen_error))
            }
        }
    }

    fun getRandPassword(n: Int): String
    {
        val characterSet = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ"

        val random = Random(System.nanoTime())
        val password = StringBuilder()

        for (i in 0 until n)
        {
            val rIndex = random.nextInt(characterSet.length)
            password.append(characterSet[rIndex])
        }

        return password.toString()
    }

    private fun showToastMessage(message: String) {
        val handler = Handler(Looper.getMainLooper())
        handler.post {
            //UI Thread work here
            Toast.makeText(applicationContext, message, Toast.LENGTH_LONG).show()
        }
    }

    fun init(context: Context, startService: Boolean) {
        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
        }
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        if (prefs.getBoolean(ProofMode.PREFS_DOPROOF, false)) {

            //add google safetynet and opentimestamps
            addDefaultNotarizationProviders()
            val intentService = Intent(context, ProofService::class.java)
            intentService.action = ProofService.ACTION_START
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                ProofMode.initBackgroundService(this)
                if (startService) context.startForegroundService(intentService)
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intentService)
            } else {
                context.startService(intentService)
            }
        }

        C2paJNI.init(this)

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
        val intentService = Intent(context, ProofService::class.java)
        context.stopService(intentService)
    }

    private fun addDefaultNotarizationProviders() {
        try {
            Class.forName("com.google.android.gms.safetynet.SafetyNetApi")
            SafetyNetCheck.setApiKey(getString(org.witness.proofmode.library.R.string.verification_api_key))

            //notarize and then write proof so we can include notarization response
            val gProvider = GoogleSafetyNetNotarizationProvider(this)
            ProofMode.addNotarizationProvider(this, gProvider)
        } catch (ce: ClassNotFoundException) {
            //SafetyNet API not available
        }
        try {
            //this may not be included in the current build
            Class.forName("com.eternitywall.ots.OpenTimestamps")
            val nProvider: NotarizationProvider = OpenTimestampsNotarizationProvider()
            ProofMode.addNotarizationProvider(this, nProvider)
        } catch (e: ClassNotFoundException) {
            //class not available
        }

        /**
         * // original idea for adding C2PA through notarization... moving now to use in camera capture directly
        val nProvider: NotarizationProvider = C2paNotarizationProvider(this)
        ProofMode.addNotarizationProvider(this, nProvider)
        **/

    }

    companion object {
        const val TAG = "ProofMode"
    }
}