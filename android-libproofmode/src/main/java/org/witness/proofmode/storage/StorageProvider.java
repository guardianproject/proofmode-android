package org.witness.proofmode.storage;

import android.net.Uri;
import android.renderscript.ScriptGroup;

import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;

public interface StorageProvider {

    public void saveStream(String hash, String identifier, InputStream stream, StorageListener listener);

    public void saveBytes(String hash, String identifier, byte[] data, StorageListener listener);

    public void saveText(String hash, String identifier, String data, StorageListener listener);

    public InputStream getInputStream (String hash, String identifier);

    public OutputStream getOutputStream (String hash, String identifier);

    public boolean proofExists (String hash);

    public boolean proofIdentifierExists (String hash, String identifier);

    public ArrayList<Uri> getProofSet (String hash);

    public InputStream getProofItem (Uri uri);

}
