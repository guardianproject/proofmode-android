package com.eternitywall.ots.attestation;

import com.eternitywall.ots.StreamDeserializationContext;
import com.eternitywall.ots.StreamSerializationContext;
import com.eternitywall.ots.Utils;

import java.sql.Time;
import java.util.Arrays;
import java.util.logging.Logger;

/**
 * Placeholder for attestations that don't support
 *
 * @see com.eternitywall.ots.attestation.TimeAttestation
 */
public class UnknownAttestation extends TimeAttestation {

    private static Logger log = Logger.getLogger(UnknownAttestation.class.getName());

    byte[] payload;

    public static byte[] _TAG = new byte[]{};

    @Override
    public byte[] _TAG() {
        return _TAG;
    }

    UnknownAttestation(byte[] tag, byte[] payload) {
        super();
        this._TAG = tag;
        this.payload = payload;
    }

    @Override
    public void serializePayload(StreamSerializationContext ctx) {
        ctx.writeBytes(this.payload);
    }

    public static UnknownAttestation deserialize(StreamDeserializationContext ctxPayload, byte[] tag) {
        byte[] payload = ctxPayload.readVarbytes(_MAX_PAYLOAD_SIZE);
        return new UnknownAttestation(tag, payload);
    }

    public String toString() {
        return "com.eternitywall.ots.attestation.UnknownAttestation " + this._TAG() + ' ' + this.payload;
    }


    @Override
    public int compareTo(TimeAttestation o) {
        UnknownAttestation ota = (UnknownAttestation) o;
        return Utils.compare(this.payload, ota.payload) ;
    }

    @Override
    public boolean equals(Object obj){
        if(!(obj instanceof UnknownAttestation)){
            return false;
        }
        if(!Arrays.equals(this._TAG(), ((UnknownAttestation)obj)._TAG())){
            return false;
        }
        if(!Arrays.equals(this.payload, ((UnknownAttestation)obj).payload)){
            return false;
        }
        return true;
    }
}
