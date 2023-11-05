package org.witness.proofmode.notaries;

import org.witness.proofmode.notarization.NotarizationListener;
import org.witness.proofmode.notarization.NotarizationProvider;

import java.io.IOException;
import java.io.InputStream;

public class ProofSignNotarizationProvider implements NotarizationProvider {
    @Override
    public void notarize(String hash, String mimeType, InputStream is, NotarizationListener listener) {

    }

    @Override
    public String getProof(String hash) throws IOException {
        return null;
    }

    @Override
    public String getNotarizationFileExtension() {
        return null;
    }
}
