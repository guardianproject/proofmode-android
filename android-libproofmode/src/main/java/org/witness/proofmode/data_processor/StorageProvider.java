package org.witness.proofmode.data_processor;

import java.io.OutputStream;

public interface StorageProvider {
    public void saveStream(String hash, OutputStream stream, StorageListener listener);
}
