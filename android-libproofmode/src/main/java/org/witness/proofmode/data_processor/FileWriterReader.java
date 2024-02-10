package org.witness.proofmode.data_processor;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;

/**
 * Writing and reading [File]s
 */
public  class FileWriterReader implements DataWriterReader {
    private final File fileOut;

    public FileWriterReader(File fileOut) {
        this.fileOut = fileOut;
    }

    @Override
    public void writeData(byte[] data) throws IOException {
        try (DataOutputStream os = new DataOutputStream(new FileOutputStream(fileOut, false))) {
            os.write(data);
            os.flush();
        }
    }

    @Override
    public byte[] readData() throws IOException {
        try (DataInputStream is = new DataInputStream(new FileInputStream(fileOut))) {
            byte[] buffer = new byte[(int) fileOut.length()];
            is.readFully(buffer);
            return buffer;
        }
    }
}