package com.eternitywall.ots;

import com.eternitywall.ots.attestation.BitcoinBlockHeaderAttestation;
import com.eternitywall.ots.attestation.EthereumBlockHeaderAttestation;
import com.eternitywall.ots.attestation.LitecoinBlockHeaderAttestation;
import com.eternitywall.ots.attestation.PendingAttestation;
import com.eternitywall.ots.attestation.TimeAttestation;
import com.eternitywall.ots.exceptions.VerificationException;
import com.eternitywall.ots.op.OpAppend;
import com.eternitywall.ots.op.OpCrypto;
import com.eternitywall.ots.op.OpSHA256;
import org.bitcoinj.core.DumpedPrivateKey;
import org.bitcoinj.core.ECKey;
import org.bitcoinj.core.NetworkParameters;

import java.io.IOException;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Logger;

/**
 * The main class for timestamp operations.
 *
 * @see OtsCli for how they are most often used
 */
public class OpenTimestamps {

    private static Logger log = Utils.getLogger(OpenTimestamps.class.getName());

    /**
     * Show information on a detached timestamp.
     *
     * @param detachedTimestampFile The DetachedTimestampFile ots.
     * @return the string representation of the timestamp.
     */
    public static String info(DetachedTimestampFile detachedTimestampFile) {
        return info(detachedTimestampFile, false);
    }

    /**
     * Show information on a detached timestamp with verbose option.
     *
     * @param detachedTimestampFile The DetachedTimestampFile ots.
     * @param verbose               Show verbose output.
     * @return the string representation of the timestamp.
     */
    public static String info(DetachedTimestampFile detachedTimestampFile, boolean verbose) {
        if (detachedTimestampFile == null) {
            return "No ots file";
        }

        String fileHash = Utils.bytesToHex(detachedTimestampFile.timestamp.msg).toLowerCase();
        String hashOp = ((OpCrypto) detachedTimestampFile.fileHashOp)._TAG_NAME();

        String firstLine = "File " + hashOp + " hash: " + fileHash + '\n';

        return firstLine + "Timestamp:\n" + detachedTimestampFile.timestamp.strTree(0, verbose);
    }

    /**
     * Show information on a timestamp.
     *
     * @param timestamp The timestamp buffered.
     * @return the string representation of the timestamp.
     */
    public static String info(Timestamp timestamp) {
        if (timestamp == null) {
            return "No timestamp";
        }

        String fileHash = Utils.bytesToHex(timestamp.msg).toLowerCase();
        String firstLine = "Hash: " + fileHash + '\n';

        return firstLine + "Timestamp:\n" + timestamp.strTree(0);
    }

    /**
     * Create timestamp with the aid of a remote calendar. May be specified multiple times.
     *
     * @param fileTimestamp The Detached Timestamp File.
     * @return The plain array buffer of stamped.
     * @throws IOException if fileTimestamp is not valid, or the stamp procedure fails.
     */
    public static Timestamp stamp(DetachedTimestampFile fileTimestamp) throws IOException {
        return OpenTimestamps.stamp(fileTimestamp, null, 0, null);
    }

    /**
     * Create timestamp with the aid of a remote calendar. May be specified multiple times.
     *
     * @param fileTimestamp The Detached Timestamp File.
     * @param calendarsUrl  The list of calendar urls.
     * @param m             The number of calendar to use.
     * @return The plain array buffer of stamped.
     * @throws IOException if fileTimestamp is not valid, or the stamp procedure fails.
     */
    public static Timestamp stamp(DetachedTimestampFile fileTimestamp, List<String> calendarsUrl, Integer m) throws IOException {
        return OpenTimestamps.stamp(fileTimestamp, calendarsUrl, m, null);
    }

    /**
     * Create timestamp with the aid of a remote calendar. May be specified multiple times.
     *
     * @param fileTimestamp       The timestamp to stamp.
     * @param calendarsUrl        The list of calendar urls.
     * @param m                   The number of calendar to use.
     * @param privateCalendarsUrl The list of private calendar urls with signature.
     * @return The plain array buffer of stamped.
     * @throws IOException if fileTimestamp is not valid, or the stamp procedure fails.
     */
    public static Timestamp stamp(DetachedTimestampFile fileTimestamp, List<String> calendarsUrl, Integer m, HashMap<String, String> privateCalendarsUrl) throws IOException {
        List<DetachedTimestampFile> fileTimestamps = new ArrayList<DetachedTimestampFile>();
        fileTimestamps.add(fileTimestamp);

        return OpenTimestamps.stamp(fileTimestamps, calendarsUrl, m, privateCalendarsUrl);
    }

    /**
     * Create timestamp with the aid of a remote calendar. May be specified multiple times.
     *
     * @param fileTimestamps      The list of timestamp to stamp.
     * @param calendarsUrl        The list of calendar urls.
     * @param m                   The number of calendar to use.
     * @param privateCalendarsUrl The list of private calendar urls with signature.
     * @return The plain array buffer of stamped.
     * @throws IOException if fileTimestamp is not valid, or the stamp procedure fails.
     */
    public static Timestamp stamp(List<DetachedTimestampFile> fileTimestamps, List<String> calendarsUrl, Integer m, HashMap<String, String> privateCalendarsUrl) throws IOException {
        // Parse parameters
        if (fileTimestamps == null || fileTimestamps.size() == 0) {
            throw new IOException();
        }

        if (privateCalendarsUrl == null) {
            privateCalendarsUrl = new HashMap<>();
        }

        if ((calendarsUrl == null || calendarsUrl.size() == 0) && (privateCalendarsUrl.size() == 0)) {
            calendarsUrl = new ArrayList<String>();
            calendarsUrl.add("https://alice.btc.calendar.opentimestamps.org");
            calendarsUrl.add("https://bob.btc.calendar.opentimestamps.org");
            calendarsUrl.add("https://finney.calendar.eternitywall.com");
        }

        if (m == null || m <= 0) {
            if (calendarsUrl.size() + privateCalendarsUrl.size() == 0) {
                m = 2;
            } else if (calendarsUrl.size() + privateCalendarsUrl.size() == 1) {
                m = 1;
            } else {
                m = calendarsUrl.size() + privateCalendarsUrl.size();
            }
        }

        if (m < 0 || m > calendarsUrl.size() + privateCalendarsUrl.size()) {
            log.severe("m cannot be greater than available calendar neither less or equal 0");
            throw new IOException();
        }

        // Build markle tree
        Timestamp merkleTip = OpenTimestamps.makeMerkleTree(fileTimestamps);

        if (merkleTip == null) {
            throw new IOException();
        }

        // Stamping
        Timestamp resultTimestamp = OpenTimestamps.create(merkleTip, calendarsUrl, m, privateCalendarsUrl);

        if (resultTimestamp == null) {
            throw new IOException();
        }

        // Result of timestamp serialization
        if (fileTimestamps.size() == 1) {
            return fileTimestamps.get(0).timestamp;
        } else {
            return merkleTip;
        }
    }

    /**
     * Create a timestamp
     *
     * @param timestamp    The timestamp.
     * @param calendarUrls List of calendar's to use.
     * @param m            Number of calendars to use.
     * @return The created timestamp.
     */
    private static Timestamp create(Timestamp timestamp, List<String> calendarUrls, Integer m, HashMap<String, String> privateCalendarUrls) {
        int capacity = calendarUrls.size() + privateCalendarUrls.size();
        ExecutorService executor = Executors.newFixedThreadPool(4);
        ArrayBlockingQueue<Optional<Timestamp>> queue = new ArrayBlockingQueue<>(capacity);

        // Submit to all private calendars with the signature key
        for (Map.Entry<String, String> entry : privateCalendarUrls.entrySet()) {
            String calendarUrl = "https://" + entry.getKey();
            String signature = entry.getValue();
            log.info("Submitting to remote private calendar " + calendarUrl);

            try {
                CalendarAsyncSubmit task = new CalendarAsyncSubmit(calendarUrl, timestamp.msg);
                ECKey key = null;

                try {
                    BigInteger privKey = new BigInteger(signature);
                    key = ECKey.fromPrivate(privKey);
                } catch (Exception e) {
                    try {
                        DumpedPrivateKey dumpedPrivateKey = new DumpedPrivateKey(NetworkParameters.prodNet(), signature);
                        key = dumpedPrivateKey.getKey();
                    } catch (Exception err) {
                        log.severe("Invalid private key");
                    }
                }

                task.setKey(key);
                task.setQueue(queue);
                executor.submit(task);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }

        // Submit to all public calendars
        for (final String calendarUrl : calendarUrls) {
            log.info("Submitting to remote calendar " + calendarUrl);

            try {
                CalendarAsyncSubmit task = new CalendarAsyncSubmit(calendarUrl, timestamp.msg);
                task.setQueue(queue);
                executor.submit(task);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }

        int count = 0;

        for (count = 0; count < capacity && count < m; count++) {
            try {
                Optional<Timestamp> optionalStamp = queue.take();

                if (optionalStamp.isPresent()) {
                    try {
                        timestamp.merge(optionalStamp.get());
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }

        if (count < m) {
            log.severe("Failed to create timestamp: requested " + String.valueOf(m) + " attestation" + ((m > 1) ? "s" : "") + " but received only " + String.valueOf(count));
        }

        //shut down the executor service now
        executor.shutdown();

        return timestamp;
    }

    /**
     * Make Merkle Tree of detached timestamps.
     *
     * @param fileTimestamps The list of DetachedTimestampFile.
     * @return merkle tip timestamp.
     */
    public static Timestamp makeMerkleTree(List<DetachedTimestampFile> fileTimestamps) {
        List<Timestamp> merkleRoots = new ArrayList<>();

        for (DetachedTimestampFile fileTimestamp : fileTimestamps) {
            byte[] bytesRandom16 = new byte[16];

            try {
                bytesRandom16 = Utils.randBytes(16);
            } catch (NoSuchAlgorithmException e) {
                e.printStackTrace();
            }

            // nonce_appended_stamp = file_timestamp.timestamp.ops.add(com.eternitywall.ots.op.OpAppend(os.urandom(16)))
            Timestamp nonceAppendedStamp = fileTimestamp.timestamp.add(new OpAppend(bytesRandom16));
            // merkle_root = nonce_appended_stamp.ops.add(com.eternitywall.ots.op.OpSHA256())
            Timestamp merkleRoot = nonceAppendedStamp.add(new OpSHA256());
            merkleRoots.add(merkleRoot);
        }

        Timestamp merkleTip = Merkle.makeMerkleTree(merkleRoots);
        return merkleTip;
    }

    /**
     * Compare and verify a detached timestamp.
     *
     * @param ots     The DetachedTimestampFile containing the proof to verify.
     * @param stamped The DetachedTimestampFile containing the stamped data.
     * @return Hashmap of block heights and timestamps indexed by chain: timestamp in seconds from 1 January 1970.
     * @throws Exception if the verification procedure fails.
     */

    public static HashMap<VerifyResult.Chains, VerifyResult> verify(DetachedTimestampFile ots, DetachedTimestampFile stamped) throws Exception {
        if (!Arrays.equals(ots.fileDigest(), stamped.fileDigest())) {
            log.severe("Expected digest " + Utils.bytesToHex(ots.fileDigest()).toLowerCase());
            log.severe("File does not match original!");
            throw new Exception("File does not match original!");
        }

        return OpenTimestamps.verify(ots.timestamp);
    }

    /**
     * Verify a timestamp.
     *
     * @param timestamp The timestamp.
     * @return HashMap of block heights and timestamps indexed by chain: timestamp in seconds from 1 January 1970.
     * @throws Exception if the verification procedure fails.
     */
    public static HashMap<VerifyResult.Chains, VerifyResult> verify(Timestamp timestamp) throws Exception {
        HashMap<VerifyResult.Chains, VerifyResult> verifyResults = new HashMap<>();

        for (Map.Entry<byte[], TimeAttestation> item : timestamp.allAttestations().entrySet()) {
            byte[] msg = item.getKey();
            TimeAttestation attestation = item.getValue();
            VerifyResult verifyResult = null;
            VerifyResult.Chains chain = null;

            try {
                if (attestation instanceof BitcoinBlockHeaderAttestation) {
                    chain = VerifyResult.Chains.BITCOIN;
                    Long time = verify((BitcoinBlockHeaderAttestation) attestation, msg);
                    int height = ((BitcoinBlockHeaderAttestation) attestation).getHeight();
                    verifyResult = new VerifyResult(time, height);
                } else if (attestation instanceof LitecoinBlockHeaderAttestation) {
                    chain = VerifyResult.Chains.LITECOIN;
                    Long time = verify((LitecoinBlockHeaderAttestation) attestation, msg);
                    int height = ((LitecoinBlockHeaderAttestation) attestation).getHeight();
                    verifyResult = new VerifyResult(time, height);
                }

                if (verifyResult != null && verifyResults.containsKey(chain)) {
                    if (verifyResult.height < verifyResults.get(chain).height) {
                        verifyResults.put(chain, verifyResult);
                    }
                }

                if (verifyResult != null && !verifyResults.containsKey(chain)) {
                    verifyResults.put(chain, verifyResult);
                }
            } catch (VerificationException e) {
                throw e;
            } catch (Exception e) {
                String text = "";

                if (chain == VerifyResult.Chains.BITCOIN) {
                    text = BitcoinBlockHeaderAttestation.chain;
                } else if (chain == VerifyResult.Chains.LITECOIN) {
                    text = LitecoinBlockHeaderAttestation.chain;
                } else if (chain == VerifyResult.Chains.ETHEREUM) {
                    text = EthereumBlockHeaderAttestation.chain;
                } else {
                    throw e;
                }

                log.severe(Utils.toUpperFirstLetter(text) + " verification failed: " + e.getMessage());
                throw e;
            }
        }
        return verifyResults;
    }

    /**
     * Verify an Bitcoin Block Header Attestation. Bitcoin verification uses a bitcoin node as default,
     * if the node is not reachable or it fails, uses Lite-client verification.
     *
     * @param attestation The BitcoinBlockHeaderAttestation attestation.
     * @param msg         The digest to verify.
     * @return The unix timestamp in seconds from 1 January 1970.
     * @throws VerificationException if it doesn't check the merkle root of the block.
     * @throws Exception             if the verification procedure fails.
     */
    public static Long verify(BitcoinBlockHeaderAttestation attestation, byte[] msg) throws VerificationException, Exception {
        Integer height = attestation.getHeight();
        BlockHeader blockInfo;

        try {
            Properties properties = BitcoinNode.readBitcoinConf();
            BitcoinNode bitcoin = new BitcoinNode(properties);
            blockInfo = bitcoin.getBlockHeader(height);
        } catch (Exception e1) {
            log.fine("There is no local node available");

            try {
                String blockHash = Esplora.blockHash(height);
                blockInfo = Esplora.block(blockHash);
                log.info("Lite-client verification, assuming block " + blockHash + " is valid");
            } catch (Exception e2) {
                e2.printStackTrace();
                throw e2;
            }
        }

        return attestation.verifyAgainstBlockheader(Utils.arrayReverse(msg), blockInfo);
    }

    /**
     * Verify an Litecoin Block Header Attestation. Litecoin verification uses only lite-client verification.
     *
     * @param attestation The LitecoinBlockHeaderAttestation attestation.
     * @param msg         The digest to verify.
     * @return The unix timestamp in seconds from 1 January 1970.
     * @throws VerificationException if it doesn't check the merkle root of the block.
     * @throws Exception             if the verification procedure fails.
     */
    public static Long verify(LitecoinBlockHeaderAttestation attestation, byte[] msg) throws VerificationException, Exception {
        Integer height = attestation.getHeight();
        BlockHeader blockInfo;

        try {
            String blockHash = blockHash = Esplora.blockHash(height);
            blockInfo = Esplora.block(blockHash);
            log.info("Lite-client verification, assuming block " + blockHash + " is valid");
        } catch (Exception e2) {
            e2.printStackTrace();
            throw e2;
        }

        return attestation.verifyAgainstBlockheader(Utils.arrayReverse(msg), blockInfo);
    }

    /**
     * Upgrade a timestamp.
     *
     * @param detachedTimestamp The DetachedTimestampFile containing the proof to verify.
     * @return a boolean representing if the timestamp has changed.
     * @throws Exception if the upgrading procedure fails.
     */
    public static boolean upgrade(DetachedTimestampFile detachedTimestamp) throws Exception {
        // Upgrade timestamp
        boolean changed = OpenTimestamps.upgrade(detachedTimestamp.timestamp);
        return changed;
    }

    /**
     * Attempt to upgrade an incomplete timestamp to make it verifiable.
     * Note that this means if the timestamp that is already complete, False will be returned as nothing has changed.
     *
     * @param timestamp The timestamp to upgrade.
     * @return a boolean representing if the timestamp has changed.
     * @throws Exception if the upgrading procedure fails.
     */
    public static boolean upgrade(Timestamp timestamp) throws Exception {
        // Check remote calendars for upgrades.
        // This time we only check PendingAttestations - we can't be as agressive.

        boolean upgraded = false;
        Set<TimeAttestation> existingAttestations = timestamp.getAttestations();

        for (Timestamp subStamp : timestamp.directlyVerified()) {
            for (TimeAttestation attestation : subStamp.attestations) {
                if (attestation instanceof PendingAttestation && !subStamp.isTimestampComplete()) {
                    String calendarUrl = new String(((PendingAttestation) attestation).getUri(), StandardCharsets.UTF_8);
                    // var calendarUrl = calendarUrls[0];
                    byte[] commitment = subStamp.msg;

                    try {
                        Calendar calendar = new Calendar(calendarUrl);
                        Timestamp upgradedStamp = OpenTimestamps.upgrade(subStamp, calendar, commitment, existingAttestations);

                        try {
                            subStamp.merge(upgradedStamp);
                        } catch (Exception e) {
                            e.printStackTrace();
                        }

                        upgraded = true;
                    } catch (Exception e) {
                        log.info(e.getMessage());
                    }
                }
            }
        }

        return upgraded;
    }

    private static Timestamp upgrade(Timestamp subStamp, Calendar calendar, byte[] commitment, Set<TimeAttestation> existingAttestations) throws Exception {
        Timestamp upgradedStamp;

        try {
            upgradedStamp = calendar.getTimestamp(commitment);

            if (upgradedStamp == null) {
                throw new Exception("Invalid stamp");
            }
        } catch (Exception e) {
            log.info("Calendar " + calendar.getUrl() + ": " + e.getMessage());
            throw e;
        }

        Set<TimeAttestation> attsFromRemote = upgradedStamp.getAttestations();

        if (attsFromRemote.size() > 0) {
            log.info("Got 1 attestation(s) from " + calendar.getUrl());
        }

        // Set difference from remote attestations & existing attestations
        Set<TimeAttestation> newAttestations = attsFromRemote;
        newAttestations.removeAll(existingAttestations);

        // changed & found_new_attestations
        // foundNewAttestations = true;
        // log.info(attsFromRemote.size + ' attestation(s) from ' + calendar.url);

        // Set union of existingAttestations & newAttestations
        existingAttestations.addAll(newAttestations);

        return upgradedStamp;
        // subStamp.merge(upgradedStamp);
        // args.cache.merge(upgraded_stamp)
        // sub_stamp.merge(upgraded_stamp)
    }
}
