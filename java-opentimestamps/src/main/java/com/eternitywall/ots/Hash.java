package com.eternitywall.ots;

/**
 * Created by casatta on 03/03/17.
 */
public class Hash {
    private byte[] value;

    public Hash(byte[] value) {
        this.value = value;
    }

    public byte[] getValue() {
        return value;
    }

    public void setValue(byte[] value) {
        this.value = value;
    }
}
