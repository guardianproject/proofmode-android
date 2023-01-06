package com.eternitywall.ots.op;

import com.eternitywall.ots.StreamDeserializationContext;
import com.eternitywall.ots.Utils;

import com.eternitywall.ots.exceptions.DeserializationException;
import java.util.Arrays;
import java.util.logging.Logger;

/**
 * Prepend a prefix to a message.
 *
 * @see OpBinary
 */
public class OpPrepend extends OpBinary {

    private static Logger log = Utils.getLogger(OpPrepend.class.getName());

    byte[] arg;

    public static byte _TAG = (byte) 0xf1;

    @Override
    public byte _TAG() {
        return OpPrepend._TAG;
    }

    @Override
    public String _TAG_NAME() {
        return "prepend";
    }

    public OpPrepend() {
        super();
        this.arg = new byte[]{};
    }

    public OpPrepend(byte[] arg_) {
        super(arg_);
        this.arg = arg_;
    }

    @Override
    public byte[] call(byte[] msg) {
        return Utils.arraysConcat(this.arg, msg);
    }

    public static Op deserializeFromTag(StreamDeserializationContext ctx, byte tag)
        throws DeserializationException {
        return OpBinary.deserializeFromTag(ctx, tag);
    }

    @Override
    public boolean equals(Object obj) {
        if (!(obj instanceof OpPrepend)) {
            return false;
        }

        return Arrays.equals(this.arg, ((OpPrepend) obj).arg);
    }

    @Override
    public int hashCode() {
        return _TAG ^ Arrays.hashCode(this.arg);
    }
}
