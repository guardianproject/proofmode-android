package org.witness.proofmode.data_processor;

import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;

public class FileDescriptorWriter implements DataWriterReader {
    private final FileDescriptor fileDescriptor;

    public FileDescriptorWriter(FileDescriptor fileDescriptor) {
        this.fileDescriptor = fileDescriptor;
    }

    @Override
    public void writeData(byte[] data) throws IOException {
        try (FileOutputStream fos = new FileOutputStream(fileDescriptor)) {
            fos.write(data);
            fos.flush();
        }
    }

    @Override
    public byte[] readData() throws IOException {
        try (FileInputStream fis = new FileInputStream(fileDescriptor)) {
            byte[] buffer = new byte[fis.available()];
            fis.read(buffer);
            return buffer;
        }
    }
}