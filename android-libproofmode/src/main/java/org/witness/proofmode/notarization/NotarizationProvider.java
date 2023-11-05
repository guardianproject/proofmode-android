package org.witness.proofmode.notarization;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;

/**
 * Created by n8fr8 on 5/31/17.
 */

public interface NotarizationProvider {

    public void notarize(String hash, String mimeType, InputStream is, NotarizationListener listener);

    public String getProof(String hash) throws IOException;

    public String getNotarizationFileExtension ();
}
