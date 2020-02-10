package org.witness.proofmode.notarization;

/**
 * Created by n8fr8 on 8/8/17.
 */

public interface NotarizationListener {

    public void notarizationSuccessful(String result);

    public void notarizationFailed(int errCode, String message);

}
