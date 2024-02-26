package org.witness.proofmode.data_processor;

import java.io.OutputStream;

public interface StorageListener {
    public void saveSuccessful(OutputStream savedStream, String hash);

    public void saveFailed(Exception exception, OutputStream stream);

}
