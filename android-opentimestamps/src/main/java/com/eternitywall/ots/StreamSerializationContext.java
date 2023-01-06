package com.eternitywall.ots;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.logging.Logger;

public class StreamSerializationContext {

    private static Logger log = Utils.getLogger(StreamSerializationContext.class.getName());

    List<Byte> buffer = new ArrayList<>();

    public StreamSerializationContext() {
        this.buffer = new ArrayList<>();
    }

    public byte[] getOutput() {
        byte[] bytes = new byte[this.buffer.size()];

        for (int i = 0; i < this.buffer.size(); i++) {
            bytes[i] = this.buffer.get(i);
        }

        return bytes;
    }

    public int getLength() {
        return this.buffer.size();
    }

    public void writeBool(boolean value) {
        if (value == true) {
            this.writeByte((byte) 0xff);
        } else {
            this.writeByte((byte) 0x00);
        }
    }

    public void writeVaruint(int value) {
        if ((value) == 0b00000000) {
            this.writeByte((byte) 0x00);
        } else {
            while (value != 0) {
                byte b = (byte) ((value & 0xff) & 0b01111111);

                if ((value) > 0b01111111) {
                    b |= 0b10000000;
                }

                this.writeByte(b);

                if ((value) <= 0b01111111) {
                    break;
                }

                value = value >> 7;
            }
        }
    }

    public void writeByte(byte value) {
        this.buffer.add(new Byte(value));
    }

    public void writeByte(Byte value) {
        this.buffer.add(value);
    }

    public void writeBytes(byte[] value) {
        for (int i = 0; i < value.length; i++) {
            this.writeByte(value[i]);
        }
    }

    public void writeVarbytes(byte[] value) {
        this.writeVaruint(value.length);
        this.writeBytes(value);
    }

    public String toString() {
        return Arrays.toString(this.getOutput());
    }
}
