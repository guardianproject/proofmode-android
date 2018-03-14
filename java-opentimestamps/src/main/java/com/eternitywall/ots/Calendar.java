package com.eternitywall.ots;
/**
 * com.eternitywall.ots.Calendar module.
 *
 * @module com.eternitywall.ots.Calendar
 * @author EternityWall
 * @license LPGL3
 */


import com.eternitywall.http.Request;
import com.eternitywall.http.Response;
import org.bitcoinj.core.ECKey;

import java.net.*;
import java.util.HashMap;
import java.util.Map;
import java.util.logging.Logger;

/** Class representing Remote com.eternitywall.ots.Calendar server interface */
public class Calendar{

    private String url;
    private ECKey key;

    private static Logger log = Logger.getLogger(Calendar.class.getName());

    /**
     * Create a RemoteCalendar.
     * @param url The server url.
     */
    public Calendar(String url) {
        this.url = url;
    }

    /**
     * Set private key.
     * @param key The private key.
     */
    public void setKey(ECKey key) {
        this.key = key;
    }

    /**
     * Get private key.
     * @return The private key.
     */
    public ECKey getKey() {
        return this.key;
    }


    /**
     * Submitting a digest to remote calendar. Returns a com.eternitywall.ots.Timestamp committing to that digest.
     * @param digest The digest hash to send.
     * @return the Timestamp received from the calendar
     */
    public Timestamp submit(byte[] digest) {
        try {

            Map<String, String> headers = new HashMap<>();
            headers.put("Accept","application/vnd.opentimestamps.v1");
            headers.put("User-Agent","java-opentimestamps");
            headers.put("Content-Type","application/x-www-form-urlencoded");

            if (key != null ) {
                String signature = key.signMessage(Utils.bytesToHex(digest).toLowerCase());
                headers.put("x-signature", signature);
            }

            URL obj = new URL(url + "/digest");
            Request task = new Request(obj);
            task.setData(digest);
            task.setHeaders(headers);
            Response response = task.call();
            byte[] body = response.getBytes();

            if (body.length > 10000) {
                log.severe("com.eternitywall.ots.Calendar response exceeded size limit");
                return null;
            }

            StreamDeserializationContext ctx = new StreamDeserializationContext(body);
            return Timestamp.deserialize(ctx, digest);

        } catch (Exception e) {
            //log.severe(e.getMessage());
            return null;
        }
    }


    /**
     * Get a timestamp for a given commitment.
     * @param commitment The digest hash to send.
     * @return the Timestamp from the calendar server (with blockchain information if already written)
     */
    public Timestamp getTimestamp(byte[] commitment) {
        try {

            Map<String, String> headers = new HashMap<>();
            headers.put("Accept","application/vnd.opentimestamps.v1");
            headers.put("User-Agent","java-opentimestamps");
            headers.put("Content-Type","application/x-www-form-urlencoded");

            URL obj = new URL(url + "/timestamp/" + Utils.bytesToHex(commitment).toLowerCase());
            Request task = new Request(obj);
            task.setHeaders(headers);
            Response response = task.call();
            byte[] body = response.getBytes();

            if (body.length > 10000) {
                log.severe("com.eternitywall.ots.Calendar response exceeded size limit");
                return null;
            }
            if(!response.isOk()) {
                log.info("com.eternitywall.ots.Calendar response a status code != 200 which is: " + response.getStatus());
                return null;
            }

            StreamDeserializationContext ctx = new StreamDeserializationContext(body);
            return Timestamp.deserialize(ctx, commitment);

        } catch (Exception e) {
            //log.severe(e.getMessage());
            return null;
        }
    }
}
