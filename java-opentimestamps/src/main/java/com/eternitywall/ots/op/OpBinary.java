package com.eternitywall.ots.op;

import com.eternitywall.ots.StreamDeserializationContext;
import com.eternitywall.ots.StreamSerializationContext;
import com.eternitywall.ots.Utils;

import java.util.Arrays;
import java.util.logging.Logger;

/**
 * Operations that act on a message and a single argument.
 *
 * @see com.eternitywall.ots.op.OpUnary
 */
public abstract class OpBinary extends Op implements Comparable<Op> {

    private static Logger log = Logger.getLogger(OpBinary.class.getName());

    byte[] arg;

    @Override
    public String _TAG_NAME() {
        return "";
    }

    OpBinary() {
        super();
        this.arg = new byte[]{};
    }

    OpBinary(byte[] arg_) {
        super();
        this.arg = arg_;
    }

    public static Op deserializeFromTag(StreamDeserializationContext ctx, byte tag) {
        byte[] arg = ctx.readVarbytes(_MAX_RESULT_LENGTH, 1);
        if (tag == OpAppend._TAG) {
            return new OpAppend(arg);
        } else if (tag == OpPrepend._TAG) {
            return new OpPrepend(arg);
        } else {
            log.severe("Unknown operation tag: " + tag  + " 0x" + String.format("%02x", tag));
            return null;
        }
    }

    @Override
    public void serialize(StreamSerializationContext ctx) {
        super.serialize(ctx);
        ctx.writeVarbytes(this.arg);
    }

    @Override
    public String toString() {
        return this._TAG_NAME() + ' ' + Utils.bytesToHex(this.arg).toLowerCase();
    }


    @Override
    public int compareTo(Op o) {
        if(o instanceof OpBinary && this._TAG()==o._TAG()) {
            return Utils.compare(this.arg, ((OpBinary) o).arg );
        }
        return this._TAG()-o._TAG();
    }

    @Override
    public int hashCode(){
        return _TAG ^ Arrays.hashCode(this.arg);
    }
}