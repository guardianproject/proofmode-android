package com.eternitywall.ots;

import java.util.Arrays;
import java.util.logging.Logger;

/**
 * Created by luca on 25/02/2017.
 */
public class StreamSerializationContext {


    private static Logger log = Logger.getLogger(StreamSerializationContext.class.getName());

    byte[] buffer = new byte[1024 * 4];
    int length = 0;

    public StreamSerializationContext() {
        this.buffer = new byte[1024 * 4];
        this.length = 0;
    }

    public byte[] getOutput() {
        return Arrays.copyOfRange(this.buffer, 0, this.length);
    }
    public int getLength() {
        return length;
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
                byte b = (byte) ((value&0xff) & 0b01111111);
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
        if (this.length >= this.buffer.length) {
            byte[] swapBuffer = Arrays.copyOf(this.buffer, this.length * 2);
            this.buffer = swapBuffer;
        }

        try {
            this.buffer[this.length] = value;
            this.length++;
        } catch (Exception e){
            e.printStackTrace();
        }
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
        return Arrays.toString(Arrays.copyOf(this.buffer, this.length));
    }

}
