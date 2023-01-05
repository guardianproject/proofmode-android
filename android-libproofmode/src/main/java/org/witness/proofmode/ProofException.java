package org.witness.proofmode;

import java.io.IOException;

public class ProofException extends IOException {

    public ProofException (String reason)
    {
        super (reason);
    }
}
