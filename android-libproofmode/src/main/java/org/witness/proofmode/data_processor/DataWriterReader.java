package org.witness.proofmode.data_processor;

import java.io.IOException;

/**
 * Interface for writing and reading files.
 *
 */
public interface DataWriterReader {
    void writeData(byte[] data) throws IOException;
    byte[] readData() throws IOException;
}


