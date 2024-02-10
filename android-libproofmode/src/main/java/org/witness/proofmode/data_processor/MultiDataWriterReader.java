package org.witness.proofmode.data_processor;

import java.io.File;
import java.io.FileDescriptor;
import java.io.IOException;

public class MultiDataWriterReader implements DataWriterReader{
    private final DataWriterReader dataWriterReader;

    public MultiDataWriterReader(File file) {
        this.dataWriterReader = new FileWriterReader(file);
    }

    public MultiDataWriterReader(FileDescriptor fileDescriptor) {
        this.dataWriterReader = new FileDescriptorWriter(fileDescriptor);
    }
    @Override
    public void writeData(byte[] data) throws IOException {

    }

    @Override
    public byte[] readData() throws IOException {
        return new byte[0];
    }
}
