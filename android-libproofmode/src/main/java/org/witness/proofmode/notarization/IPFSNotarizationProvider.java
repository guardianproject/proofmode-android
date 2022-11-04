package org.witness.proofmode.notarization;

import android.content.Context;

import java.io.IOException;
import java.io.InputStream;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import threads.lite.IPFS;
import threads.lite.cid.Cid;
import threads.lite.cid.Multiaddr;
import threads.lite.core.Connection;
import threads.lite.core.Parameters;
import threads.lite.core.Session;
import threads.lite.utils.TimeoutCancellable;

public class IPFSNotarizationProvider  implements NotarizationProvider  {

    IPFS ipfs = null;
    Session session = null;

    public IPFSNotarizationProvider (Context context) throws Exception {
        ipfs = IPFS.getInstance(context);  // now it is possible to work with ipfs
        session = ipfs.createSession(); // simple default session
    }

    @Override
    public void notarize(String hash, InputStream is, NotarizationListener listener) {

        try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
                Cid cid = ipfs.storeText(session, hash);
                listener.notarizationSuccessful(hash, "ipfs://" + cid.String());
            }

        } catch (Exception e) {
            e.printStackTrace();
            listener.notarizationFailed(-1,e.getMessage());
        }


    }

    @Override
    public String getProof(String hash) throws IOException {
        return null;
    }
}
