package org.witness.proofmode;

import android.app.Application;
import android.content.Intent;

import org.spongycastle.jce.provider.BouncyCastleProvider;
import org.witness.proofmode.service.MediaListenerService;

import java.security.Security;

/**
 * Created by n8fr8 on 10/10/16.
 */
public class ProofModeApp extends Application {


    static {
        Security.addProvider(new BouncyCastleProvider());
    }

    @Override
    public void onCreate() {
        super.onCreate();

        startService(new Intent(getBaseContext(), MediaListenerService.class));

    }
}
