package com.eternitywall.ots.op;

import com.eternitywall.ots.StreamDeserializationContext;
import com.eternitywall.ots.Utils;

import java.util.Arrays;
import java.util.Objects;
import java.util.logging.Logger;

/**
 * Append a suffix to a message.
 *
 * @see com.eternitywall.ots.op.OpBinary
 */
public class OpAppend extends OpBinary {

    private static Logger log = Logger.getLogger(OpAppend.class.getName());

    byte[] arg;

    public static byte _TAG = (byte) 0xf0;


    @Override
    public byte _TAG() {
        return OpAppend._TAG;
    }

    @Override
    public String _TAG_NAME() {
        return "append";
    }

    public OpAppend() {
        super();
        this.arg = new byte[]{};
    }

    public OpAppend(byte[] arg_) {
        super(arg_);
        this.arg = arg_;
    }

    @Override
    public byte[] call(byte[] msg) {
        return Utils.arraysConcat(msg, this.arg);
    }

    public static Op deserializeFromTag(StreamDeserializationContext ctx, byte tag) {
        return OpBinary.deserializeFromTag(ctx, tag);
    }

    @Override
    public boolean equals(Object obj) {
        if(!(obj instanceof OpAppend)){
            return false;
        }
        return Arrays.equals(this.arg,((OpAppend)obj).arg);
    }

    @Override
    public int hashCode(){
        return _TAG ^ Arrays.hashCode(this.arg);
    }
}