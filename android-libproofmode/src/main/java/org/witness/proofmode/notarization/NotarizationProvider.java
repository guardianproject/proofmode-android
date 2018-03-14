package org.witness.proofmode.notarization;

import java.io.File;
import java.io.IOException;

/**
 * Created by n8fr8 on 5/31/17.
 */

public interface NotarizationProvider {

    public void notarize(String hash, File fileMedia, NotarizationListener listener);

    public String getProof(String hash) throws IOException;

}
