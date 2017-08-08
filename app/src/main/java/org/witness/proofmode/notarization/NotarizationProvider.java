package org.witness.proofmode.notarization;

import java.io.File;

/**
 * Created by n8fr8 on 5/31/17.
 */

public interface NotarizationProvider {

    public String notarize (String hash, File fileMedia);

    public String getProofURI (String hash);

}
