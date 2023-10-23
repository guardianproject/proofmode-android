package org.witness.proofmode.notarization;

import java.io.File;
import java.io.InputStream;

/**
 * Created by n8fr8 on 8/8/17.
 */

public interface NotarizationListener {

    public void notarizationSuccessful(String hash, String result);

    public void notarizationSuccessful(String hash, byte[] result);

    public void notarizationSuccessful(String hash, File fileTmp);

    public void notarizationFailed(int errCode, String message);

}
