package com.eternitywall.ots;
/**
 * com.eternitywall.ots.Timestamp module.
 *
 * @module com.eternitywall.ots.Timestamp
 * @author EternityWall
 * @license LPGL3
 */


import com.eternitywall.ots.attestation.BitcoinBlockHeaderAttestation;
import com.eternitywall.ots.attestation.TimeAttestation;
import com.eternitywall.ots.op.Op;
import com.eternitywall.ots.attestation.*;
import java.util.Map.Entry;
import java.util.*;
import java.util.logging.Logger;

/**
 * Class representing com.eternitywall.ots.Timestamp interface
 * Proof that one or more attestations commit to a message.
 * The proof is in the form of a tree, with each node being a message, and the
 * edges being operations acting on those messages. The leafs of the tree are
 * attestations that attest to the time that messages in the tree existed prior.
 */
public class Timestamp {


    private static Logger log = Logger.getLogger(Timestamp.class.getName());

    public byte[] msg;
    public List<TimeAttestation> attestations;
    public HashMap<Op, Timestamp> ops;

    /**
     * Create a com.eternitywall.ots.Timestamp object.
     * @param msg - Desc
     */
    public Timestamp(byte[] msg) {
        this.msg = msg;
        this.attestations = new ArrayList<>();
        this.ops = new HashMap<>();
    }


    /**
     * Deserialize a com.eternitywall.ots.Timestamp.
     * Because the serialization format doesn't include the message that the
     * timestamp operates on, you have to provide it so that the correct
     * operation results can be calculated.
     * The message you provide is assumed to be correct; if it causes a op to
     * raise MsgValueError when the results are being calculated (done
     * immediately, not lazily) DeserializationError is raised instead.
     * @param ctx - The stream deserialization context.
     * @param initialMsg - The initial message.
     * @return The generated com.eternitywall.ots.Timestamp.
     */
    public static Timestamp deserialize(StreamDeserializationContext ctx, byte[] initialMsg) {
        Timestamp self = new Timestamp(initialMsg);

        byte tag = ctx.readBytes(1)[0];
        while ((tag&0xff) == 0xff) {
            byte current = ctx.readBytes(1)[0];
            doTagOrAttestation(self, ctx, current, initialMsg);
            tag = ctx.readBytes(1)[0];
        }
        doTagOrAttestation(self, ctx, tag, initialMsg);

        return self;
    }

    private static void doTagOrAttestation(Timestamp self, StreamDeserializationContext ctx, byte tag, byte[] initialMsg) {
        if ((tag&0xff) == 0x00) {
            TimeAttestation attestation = TimeAttestation.deserialize(ctx);
            self.attestations.add(attestation);
        } else {

            Op op = Op.deserializeFromTag(ctx, tag);
            byte[] result = op.call(initialMsg);

            Timestamp stamp = Timestamp.deserialize(ctx, result);
            self.ops.put(op, stamp);
        }
    }

    /**
     * Create a Serialize object.
     * @param ctx - The stream serialization context.
     */
    public void serialize(StreamSerializationContext ctx) {

        // sort
        List<TimeAttestation> sortedAttestations = this.attestations;
        Collections.sort(sortedAttestations);
        
        if (sortedAttestations.size() > 1) {
            for (int i = 0; i < sortedAttestations.size() - 1; i++) {
                ctx.writeBytes(new byte[]{(byte) 0xff, (byte) 0x00});
                sortedAttestations.get(i).serialize(ctx);
            }
        }
        if (this.ops.isEmpty()) {
            ctx.writeByte((byte) 0x00);
            if (!sortedAttestations.isEmpty()) {
                sortedAttestations.get(sortedAttestations.size() - 1).serialize(ctx);
            }
        } else if (!this.ops.isEmpty()) {
            if (!sortedAttestations.isEmpty()) {
                ctx.writeBytes(new byte[]{(byte) 0xff, (byte) 0x00});
                sortedAttestations.get(sortedAttestations.size() - 1).serialize(ctx);
            }

            int counter = 0;
            List<Map.Entry<Op, Timestamp>> list = sortToList(this.ops.entrySet());
            for (Map.Entry<Op, Timestamp> entry : list){
                Timestamp stamp = entry.getValue();
                Op op = entry.getKey();
                if (counter < this.ops.size() - 1) {
                    ctx.writeBytes(new byte[]{(byte) 0xff});
                    counter++;
                }

                op.serialize(ctx);
                stamp.serialize(ctx);
            }

        }
    }

    /**
     * Add all operations and attestations from another timestamp to this one.
     * @param other - Initial other com.eternitywall.ots.Timestamp to merge.
     */
    public void merge(Timestamp other) throws Exception {
        if (!Arrays.equals(this.msg, other.msg)) {
            //log.severe("Can\'t merge timestamps for different messages together");
            throw new Exception("Can\'t merge timestamps for different messages together");
        }

        for (final TimeAttestation attestation : other.attestations) {
            this.attestations.add(attestation);
        }

        for (Map.Entry<Op, Timestamp> entry : other.ops.entrySet()) {
            Timestamp otherOpStamp = entry.getValue();
            Op otherOp = entry.getKey();

            Timestamp ourOpStamp = this.ops.get(otherOp);
            if (ourOpStamp == null) {
                ourOpStamp = new Timestamp(otherOp.call(this.msg));
                this.ops.put(otherOp, ourOpStamp);
            }
            ourOpStamp.merge(otherOpStamp);
        }
    }

    public TimeAttestation shrink() throws Exception {
        // Get all attestations
        HashMap<byte[], TimeAttestation> allAttestations = this.allAttestations();
        if (allAttestations.size() == 0) {
            throw new Exception();
        } else if (allAttestations.size() == 1) {
            return allAttestations.values().iterator().next();
        }

        if (this.ops.size() == 0) {
            throw new Exception();
        }

        if (this.getAttestations().size() == 1) {
            return this.getAttestations().iterator().next();
        }

        // Search first BitcoinBlockHeaderAttestation / EthereumBlockHeaderAttestation
        TimeAttestation minAttestation = null;
        for (Map.Entry<Op, Timestamp> entry : this.ops.entrySet()) {
            Timestamp timestamp = entry.getValue();
            //Op op = entry.getKey();
            TimeAttestation attestation = timestamp.shrink();

            if (attestation instanceof BitcoinBlockHeaderAttestation ||
                    attestation instanceof EthereumBlockHeaderAttestation) {

                if (minAttestation == null) {
                    minAttestation = attestation;
                } else {
                    if (minAttestation instanceof BitcoinBlockHeaderAttestation && attestation instanceof BitcoinBlockHeaderAttestation
                            && ((BitcoinBlockHeaderAttestation) minAttestation).getHeight() > ((BitcoinBlockHeaderAttestation) attestation).getHeight()) {
                        minAttestation = attestation;
                    } else if (minAttestation instanceof EthereumBlockHeaderAttestation && attestation instanceof EthereumBlockHeaderAttestation
                            && ((EthereumBlockHeaderAttestation) minAttestation).getHeight() > ((EthereumBlockHeaderAttestation) attestation).getHeight()) {
                        minAttestation = attestation;
                    }

                }
            }
        }

        for (Map.Entry<Op, Timestamp> entry : this.ops.entrySet()) {
            Timestamp timestamp = entry.getValue();
            Op op = entry.getKey();
            TimeAttestation attestation = timestamp.shrink();
            if (!minAttestation.equals(attestation)) {
                this.ops.remove(op);
            }
        }

        return this.getAttestations().iterator().next();

    }

    /*
     * Print the digest of the timestamp.
     * @return The byte[] digest string.
     */
    public byte[] getDigest(){
        return this.msg;
    }

    /**
     * Print as memory hierarchical object.
     * @param indent - Initial hierarchical indention.
     * @return The output string.
     */
    public String toString(int indent) {
        StringBuilder builder = new StringBuilder();
        builder.append( Timestamp.indention(indent) + "msg: " + Utils.bytesToHex(this.msg).toLowerCase() + "\n");
        builder.append( Timestamp.indention(indent) + this.attestations.size() + " attestations: \n");
        int i = 0;
        for (final TimeAttestation attestation : this.attestations) {
            builder.append( Timestamp.indention(indent) + "[" + i + "] " + attestation.toString() + "\n");
            i++;
        }

        i = 0;
        builder.append( Timestamp.indention(indent) + this.ops.size() + " ops: \n");

        for (Map.Entry<Op, Timestamp> entry : this.ops.entrySet()) {
            Timestamp stamp = entry.getValue();
            Op op = entry.getKey();

            builder.append( Timestamp.indention(indent) + "[" + i + "] op: " + op.toString() + "\n");
            builder.append( Timestamp.indention(indent) + "[" + i + "] timestamp: \n");
            builder.append( stamp.toString(indent + 1));
            i++;
        }

        builder.append( '\n' );
        return builder.toString();
    }

    /**
     * Indention function for printing tree.
     * @param pos - Initial hierarchical indention.
     * @return The output space string.
     */
    public static String indention(int pos) {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < pos; i++) {
            builder.append("    ");
        }
        return builder.toString();
    }

    /**
     * Print as tree hierarchical object.
     * @param indent - Initial hierarchical indention.
     * @return The output string.
     */
    public String strTree(int indent) {
        StringBuilder builder = new StringBuilder();
        if (!this.attestations.isEmpty()) {
            for (final TimeAttestation attestation : this.attestations) {
                builder.append( Timestamp.indention(indent));
                builder.append( "verify " + attestation.toString() + '\n');

            }
        }

        if (this.ops.size() > 1) {
            TreeMap<Op, Timestamp> ordered = new TreeMap<>(this.ops);

            for (Map.Entry<Op, Timestamp> entry : ordered.entrySet()) {
                Timestamp timestamp = entry.getValue();
                Op op = entry.getKey();
                builder.append( Timestamp.indention(indent));
                builder.append( " -> ");
                builder.append( op.toString() + '\n');
                builder.append( timestamp.strTree(indent + 1));
            }
        } else if (this.ops.size() > 0) {
            // output += com.eternitywall.ots.Timestamp.indention(indent);
            for (Map.Entry<Op, Timestamp> entry : this.ops.entrySet()) {
                Timestamp timestamp = entry.getValue();
                Op op = entry.getKey();
                builder.append( Timestamp.indention(indent));
                builder.append( op.toString() + '\n');
                // output += ' ( ' + com.eternitywall.ots.Utils.bytesToHex(this.msg) + ' ) ';
                // output += '\n';
                builder.append( timestamp.strTree(indent));
            }
        }
        return builder.toString();
    }

    /**
     * Print as tree extended hierarchical object.
     * @param timestamp - desc
     * @param indent - Initial hierarchical indention.
     * @return The output string.
     */
    public static String strTreeExtended(Timestamp timestamp, int indent) {
        StringBuilder builder = new StringBuilder();

        if (!timestamp.attestations.isEmpty()) {
            for (final TimeAttestation attestation : timestamp.attestations) {
                builder.append( Timestamp.indention(indent))
                    .append( "verify " + attestation.toString())
                    .append( " (" + Utils.bytesToHex(timestamp.msg).toLowerCase() + ") ")
                    //.append( " ["+com.eternitywall.ots.Utils.bytesToHex(timestamp.msg)+"] ")
                    .append( '\n');
            }
        }

        if (timestamp.ops.size() > 1) {

            for (Map.Entry<Op, Timestamp> entry : timestamp.ops.entrySet()) {
                Timestamp ts = entry.getValue();
                Op op = entry.getKey();
                builder.append( Timestamp.indention(indent))
                    .append( " -> ")
                    .append( op.toString())
                    .append( " (" + Utils.bytesToHex(timestamp.msg).toLowerCase() + ") ")
                    .append( '\n')
                    .append( Timestamp.strTreeExtended(ts, indent + 1));
            }
        } else if (timestamp.ops.size() > 0) {
            builder.append( Timestamp.indention(indent));
            for (Map.Entry<Op, Timestamp> entry : timestamp.ops.entrySet()) {
                Timestamp ts = entry.getValue();
                Op op = entry.getKey();
                builder.append( Timestamp.indention(indent))
                    .append( op.toString())
                    .append( " ( " + Utils.bytesToHex(timestamp.msg).toLowerCase() + " ) ")
                    .append( '\n')
                    .append( Timestamp.strTreeExtended(ts, indent));
            }
        }
        return builder.toString();
    }

    /** Set of al Attestations.
     * @return Array of all sub timestamps with attestations.
     */
    public List<Timestamp> directlyVerified() {
        if (!this.attestations.isEmpty()) {
            List<Timestamp> list = new ArrayList<>();
            list.add(this);
            return list;
        }
        List<Timestamp> list = new ArrayList<>();

        for (Map.Entry<Op, Timestamp> entry : this.ops.entrySet()) {
            Timestamp ts = entry.getValue();
            //Op op = entry.getKey();

            List<Timestamp> result = ts.directlyVerified();
            list.addAll(result);
        }
        return list;
    }

    /** Set of al Attestations.
     * @return Set of all timestamp attestations.
     */
    public Set<TimeAttestation> getAttestations() {
        Set set = new HashSet<TimeAttestation>();
        for (Map.Entry<byte[], TimeAttestation> item : this.allAttestations().entrySet()) {
            //byte[] msg = item.getKey();
            TimeAttestation attestation = item.getValue();
            set.add(attestation);
        }
        return set;
    }

    /** Determine if timestamp is complete and can be verified.
     * @return True if the timestamp is complete, False otherwise.
     */
    public Boolean isTimestampComplete() {
        for (Map.Entry<byte[], TimeAttestation> item : this.allAttestations().entrySet()) {
            //byte[] msg = item.getKey();
            TimeAttestation attestation = item.getValue();
            if (attestation instanceof BitcoinBlockHeaderAttestation) {
                return true;
            }
        }
        return false;
    }

    /**
     * Iterate over all attestations recursively
     * @return Returns iterable of (msg, attestation)
     */
    public HashMap<byte[], TimeAttestation> allAttestations() {
        HashMap<byte[], TimeAttestation> map = new HashMap<>();
        for (TimeAttestation attestation : this.attestations) {
            map.put(this.msg, attestation);
        }
        for (Map.Entry<Op, Timestamp> entry : this.ops.entrySet()) {
            Timestamp ts = entry.getValue();
            //Op op = entry.getKey();

            HashMap<byte[], TimeAttestation> subMap = ts.allAttestations();
            for (Map.Entry<byte[], TimeAttestation> item : subMap.entrySet()) {
                byte[] msg = item.getKey();
                TimeAttestation attestation = item.getValue();
                map.put(msg, attestation);
            }
        }
        return map;
    }

    /**
     * Iterate over all tips recursively
     * @return Returns iterable of (msg, attestation)
     */
    public Set<byte[]> allTips() {

        Set<byte[]> set = new HashSet<>();
        if (this.ops.size() == 0){
            set.add(this.msg);
        }
        for (Map.Entry<Op, Timestamp> entry : this.ops.entrySet()) {
            Timestamp ts = entry.getValue();
            //Op op = entry.getKey();

            Set<byte[]> subSet = ts.allTips();
            for (byte[] msg : subSet) {
                set.add(msg);
            }
        }
        return set;
    }

    /**
     * Compare timestamps
     * @return Returns true if timestamps are equals
     */
    public boolean equals(Timestamp timestamp){
        if (Arrays.equals(this.getDigest(),timestamp.getDigest()) == false){
            return false;
        }

        // Check attestations
        if(this.attestations.size() != timestamp.attestations.size()){
            return false;
        }
        for (int i=0 ; i < this.attestations.size(); i++){
            TimeAttestation ta1 = this.attestations.get(i);
            TimeAttestation ta2 = timestamp.attestations.get(i);
            if(!(ta1.equals( ta2 ))){
                return false;
            }
        }

        // Check operations
        if (this.ops.size() != timestamp.ops.size()){
            return false;
        }

        // Order list of operations
        List<Map.Entry<Op, Timestamp>> list1 = sortToList(this.ops.entrySet());
        List<Map.Entry<Op, Timestamp>> list2 = sortToList(this.ops.entrySet());
        for (int i=0 ; i<list1.size() ; i++){
            Op op1 = list1.get(i).getKey();
            Op op2 = list2.get(i).getKey();
            if (!op1.equals(op2)){
                return false;
            }
            Timestamp t1 = list1.get(i).getValue();
            Timestamp t2 = list2.get(i).getValue();
            if (!t1.equals(t2)){
                return false;
            }
        }
        return true;
    }

    /**
     * Add Op to current timestamp and return the sub stamp
     * @param op - The operation to insert
     * @return Returns the sub timestamp
     */
    public Timestamp add(Op op){
        for (Map.Entry<Op, Timestamp> entry : this.ops.entrySet()) {
            Timestamp ts = entry.getValue();
            Op op_ = entry.getKey();
            if (op.hashCode() == op_.hashCode()){
                return ts;
            }
        }
        /*
        containsKey not always works
        if (this.ops.containsKey(op)) {
            return this.ops.get(op);
        }*/

        Timestamp stamp = new Timestamp(op.call(this.msg));
        if(!this.ops.containsKey(op)) {
            this.ops.put(op, stamp);
        }
        return stamp;
    }

    /*public void put(Op op, Timestamp stamp){
        boolean found = false;
        for (Map.Entry<Op, Timestamp> entry : this.ops.entrySet()) {
            Timestamp ts = entry.getValue();
            Op op_ = entry.getKey();
            if (op.hashCode() == op_.hashCode()){
                found = true;
            }
        }
        if(found == true){
            found=false;
        }
        this.ops.put(op, stamp);
        found=false;
        /*
        containsKey not always works / files compatibility problem
        if (this.ops.containsKey(op)) {
            this.ops.remove(op)
            this.ops.replace(op,stamp);
        } else {
            this.ops.put(op, stamp);
        }*/


    /**
     * Retrieve
     * @param setEntries - The entries set of ops hashmap
     * @return Returns the sorted list of map entries
     */
    public List<Map.Entry<Op, Timestamp>> sortToList(Set<Entry<Op, Timestamp>> setEntries){
        List<Map.Entry<Op, Timestamp>> entries = new ArrayList<>(setEntries);
        Collections.sort(entries, new Comparator<Map.Entry<Op, Timestamp>>() {
            @Override
            public int compare(Entry<Op, Timestamp> a, Entry<Op, Timestamp> b) {
                return a.getKey().compareTo(b.getKey());
            }
        });
        return entries;
    }

}