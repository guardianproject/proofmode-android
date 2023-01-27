package org.witness.proofmode;

import static org.witness.proofmode.ProofMode.PREFS_DOPROOF;
import static org.witness.proofmode.ProofService.ACTION_START;

import android.content.Context;

import androidx.multidex.MultiDexApplication;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.preference.PreferenceManager;
import android.util.Log;
import android.widget.Toast;

import org.bouncycastle.openpgp.PGPException;
import org.bouncycastle.openpgp.PGPPublicKey;
import org.witness.proofmode.crypto.pgp.PgpUtils;
import org.witness.proofmode.notaries.GoogleSafetyNetNotarizationProvider;
import org.witness.proofmode.notaries.OpenTimestampsNotarizationProvider;
import org.witness.proofmode.notaries.SafetyNetCheck;
import org.witness.proofmode.notarization.NotarizationProvider;

import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import timber.log.Timber;

/**
 * Created by n8fr8 on 10/10/16.
 */
public class ProofModeApp extends MultiDexApplication {


    public final static String TAG = "ProofMode";

    @Override
    public void onCreate() {
        super.onCreate();

        init(this, false);

    }

    public void checkAndGeneratePublicKey ()
    {

        Executors.newSingleThreadExecutor().execute(() -> {
            //Background work here
            String pubKey = null;

            try {
                pubKey = PgpUtils.getInstance(getApplicationContext()).getPublicKeyFingerprint();
                showToastMessage(getString(R.string.pub_key_id) + " " + pubKey);
            } catch (PGPException e) {
                Timber.e(e,"error getting public key");
                showToastMessage(getString(R.string.pub_key_gen_error));
            } catch (IOException e) {
                Timber.e(e,"error getting public key");
                showToastMessage(getString(R.string.pub_key_gen_error));
            }

        });
    }

    private void showToastMessage (String message) {
        Handler handler = new Handler(Looper.getMainLooper());
        handler.post(() -> {
            //UI Thread work here
            Toast.makeText(getApplicationContext(), message,Toast.LENGTH_LONG).show();

        });
    }

    public void init (Context context, boolean startService)
    {

        if (BuildConfig.DEBUG) {
            Timber.plant(new Timber.DebugTree());
        }

        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);

        if (prefs.getBoolean(PREFS_DOPROOF,false)) {

            //add google safetynet and opentimestamps
            addDefaultNotarizationProviders();

            Intent intentService = new Intent(context, ProofService.class);
            intentService.setAction(ACTION_START);

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                ProofMode.initBackgroundService(this);

                if (startService)
                    context.startForegroundService ( intentService );

            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService ( intentService );
            } else {
                context.startService ( intentService );
            }

        }
    }

    public void cancel (Context context)
    {

        Intent intentService = new Intent(context, ProofService.class);
        context.stopService(intentService);

    }

    /** A tree which logs important information for crash reporting. */
    private static class CrashReportingTree extends Timber.Tree {
        @Override protected void log(int priority, String tag, String message, Throwable t) {
            if (priority == Log.VERBOSE || priority == Log.DEBUG) {
                return;
            }
        }
    }

    private void addDefaultNotarizationProviders ()
    {
        try {

            Class.forName("com.google.android.gms.safetynet.SafetyNetApi");
            SafetyNetCheck.setApiKey(getString(org.witness.proofmode.library.R.string.verification_api_key));

            //notarize and then write proof so we can include notarization response
            final GoogleSafetyNetNotarizationProvider gProvider = new GoogleSafetyNetNotarizationProvider(this);
            ProofMode.addNotarizationProvider(this, gProvider);
        }
        catch (ClassNotFoundException ce)
        {
            //SafetyNet API not available
        }

        try {
            //this may not be included in the current build
            Class.forName("com.eternitywall.ots.OpenTimestamps");

            final NotarizationProvider nProvider = new OpenTimestampsNotarizationProvider();
            ProofMode.addNotarizationProvider(this, nProvider);
        }
        catch (ClassNotFoundException e)
        {
            //class not available
        }

    }

}
