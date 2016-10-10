package org.witness.proofmode;

import android.app.Application;

import org.spongycastle.jce.provider.BouncyCastleProvider;

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
    }
}
