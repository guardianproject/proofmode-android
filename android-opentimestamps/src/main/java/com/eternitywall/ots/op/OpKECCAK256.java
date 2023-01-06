package com.eternitywall.ots.op;

import com.eternitywall.ots.StreamDeserializationContext;
import com.eternitywall.ots.Utils;
import com.eternitywall.ots.crypto.KeccakDigest;

import java.util.logging.Logger;

/**
 * Cryptographic Keccak256 operation.
 * Cryptographic operation tag numbers taken from RFC4880, although it's not
 * guaranteed that they'll continue to match that RFC in the future.
 *
 * @see OpCrypto
 */
public class OpKECCAK256 extends OpCrypto {

    private static Logger log = Utils.getLogger(OpKECCAK256.class.getName());
    private KeccakDigest digest = new KeccakDigest(256);

    public static byte _TAG = (byte) 103;

    @Override
    public byte _TAG() {
        return OpKECCAK256._TAG;
    }

    @Override
    public String _TAG_NAME() {
        return "keccak256";
    }

    @Override
    public String _HASHLIB_NAME() {
        return "keccak256";
    }

    @Override
    public int _DIGEST_LENGTH() {
        return digest.getDigestSize();
    }

    public OpKECCAK256() {
        super();
    }

    @Override
    public byte[] call(byte[] msg) {
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
        return (obj instanceof OpKECCAK256);
    }
}