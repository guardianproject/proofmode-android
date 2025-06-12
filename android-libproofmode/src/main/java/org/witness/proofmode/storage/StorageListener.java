package org.witness.proofmode.storage;

import java.io.OutputStream;

public interface StorageListener {
    public void saveSuccessful(String hash, String uri);

    public void saveFailed(Exception exception);

}
