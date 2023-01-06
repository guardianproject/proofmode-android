package com.eternitywall.ots.op;

import com.eternitywall.ots.StreamDeserializationContext;
import com.eternitywall.ots.Utils;
import com.eternitywall.ots.crypto.RIPEMD160Digest;

import java.util.logging.Logger;

/**
 * Cryptographic RIPEMD160 operation.
 * Cryptographic operation tag numbers taken from RFC4880, although it's not
 * guaranteed that they'll continue to match that RFC in the future.
 *
 * @see OpCrypto
 */
public class OpRIPEMD160 extends OpCrypto {

    private static Logger log = Utils.getLogger(OpRIPEMD160.class.getName());

    public static byte _TAG = 0x03;

    @Override
    public byte _TAG() {
        return OpRIPEMD160._TAG;
    }

    @Override
    public String _TAG_NAME() {
        return "ripemd160";
    }

    @Override
    public String _HASHLIB_NAME() {
        return "ripemd160";
    }

    @Override
    public int _DIGEST_LENGTH() {
        return 20;
    }

    public OpRIPEMD160() {
        super();
    }

    @Override
    public byte[] call(byte[] msg) {
        RIPEMD160Digest digest = new RIPEMD160Digest();
        digest.update(msg, 0, msg.length);
        byte[] hash = new byte[digest.getDigestSize()];
        digest.doFinal(hash, 0);

        return hash;
    }

    public static Op deserializeFromTag(StreamDeserializationContext ctx, byte tag) {
        return OpCrypto.deserializeFromTag(ctx, tag);
    }

    @Override
    public boolean equals(Object obj) {
        return (obj instanceof OpRIPEMD160);
    }

    @Override
    public int hashCode() {
        return _TAG;
    }
}
