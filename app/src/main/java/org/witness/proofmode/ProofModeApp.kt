package org.witness.proofmode

import android.content.Context
import androidx.multidex.MultiDexApplication
import org.witness.proofmode.crypto.pgp.PgpUtils
import org.witness.proofmode.R
import org.bouncycastle.openpgp.PGPException
import android.os.Looper
import android.widget.Toast
import android.content.SharedPreferences
import org.witness.proofmode.ProofMode
import android.content.Intent
import org.witness.proofmode.ProofService
import android.os.Build
import android.os.Handler
import android.preference.PreferenceManager
import android.util.Log
import org.witness.proofmode.ProofModeConstants.PREFS_KEY_PASSPHRASE
import org.witness.proofmode.ProofModeConstants.PREFS_KEY_PASSPHRASE_DEFAULT
import org.witness.proofmode.notaries.SafetyNetCheck
import org.witness.proofmode.notaries.GoogleSafetyNetNotarizationProvider
import org.witness.proofmode.notarization.NotarizationProvider
import org.witness.proofmode.notaries.OpenTimestampsNotarizationProvider
import org.witness.proofmode.org.witness.proofmode.notaries.C2paNotarizationProvider
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
        val nProvider: NotarizationProvider = C2paNotarizationProvider()
        ProofMode.addNotarizationProvider(this, nProvider)

    }

    companion object {
        const val TAG = "ProofMode"
    }
}