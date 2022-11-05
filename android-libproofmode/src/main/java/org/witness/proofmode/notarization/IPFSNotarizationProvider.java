package org.witness.proofmode.notarization;

import android.content.Context;

import com.google.protobuf.ByteString;

import java.io.IOException;
import java.io.InputStream;

import crypto.pb.Crypto;
import threads.lite.IPFS;
import threads.lite.cid.Cid;
import threads.lite.core.Keys;
import threads.lite.core.Session;
import threads.lite.crypto.Key;

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
