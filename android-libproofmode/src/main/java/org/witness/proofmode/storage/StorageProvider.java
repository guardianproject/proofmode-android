package org.witness.proofmode.storage;

import android.net.Uri;

import java.io.InputStream;
import java.util.ArrayList;

public interface StorageProvider {

    public void saveStream(String hash, String identifier, InputStream stream, StorageListener listener);

    public void saveBytes(String hash, String identifier, byte[] data, StorageListener listener);

    /**
     * Appends [data] as a new line for [identifier] under [hash] (historical CSV-friendly behavior).
     */
    public void saveText(String hash, String identifier, String data, StorageListener listener);

    /**
     * Writes [data] as the full contents of [identifier] under [hash], creating or replacing the file.
     * Use for single-value tip files (Filebase/Share URI sidecars, Composite {@code *.uri}).
     */
    public void replaceText(String hash, String identifier, String data, StorageListener listener);

    public InputStream getInputStream (String hash, String identifier);

   // public OutputStream getOutputStream (String hash, String identifier);

    public boolean proofExists (String hash);

    public boolean proofIdentifierExists (String hash, String identifier);

    public ArrayList<Uri> getProofSet (String hash);

    public InputStream getProofItem (Uri uri);

}
