package com.eternitywall.ots;

import com.eternitywall.http.Request;
import com.eternitywall.http.Response;
import com.eternitywall.ots.exceptions.CommitmentNotFoundException;
import com.eternitywall.ots.exceptions.DeserializationException;
import com.eternitywall.ots.exceptions.ExceededSizeException;
import com.eternitywall.ots.exceptions.UrlException;
import org.bitcoinj.core.ECKey;

import java.net.URL;
import java.util.HashMap;
import java.util.Map;
import java.util.logging.Logger;

/**
 * Class representing remote calendar server interface.
 */
public class Calendar {

    private String url;
    private ECKey key;

    private static Logger log = Utils.getLogger(Calendar.class.getName());

    /**
     * Create a RemoteCalendar.
     *
     * @param url The server url.
     */
    public Calendar(String url) {
        this.url = url;
    }

    /**
     * Set private key.
     *
     * @param key The private key.
     */
    public void setKey(ECKey key) {
        this.key = key;
    }

    /**
     * Get private key.
     *
     * @return The private key.
     */
    public ECKey getKey() {
        return this.key;
    }

    /**
     * Get calendar url.
     *
     * @return The calendar url.
     */
    public String getUrl() {
        return this.url;
    }

    /**
     * Submitting a digest to remote calendar. Returns a com.eternitywall.ots.Timestamp committing to that digest.
     *
     * @param digest The digest hash to send.
     * @return the Timestamp received from the calendar.
     * @throws ExceededSizeException if response is too big.
     * @throws UrlException          if url is not reachable.
     * @throws DeserializationException    if the data is corrupt
     */
    public Timestamp submit(byte[] digest)
        throws ExceededSizeException, UrlException, DeserializationException {
        try {
            Map<String, String> headers = new HashMap<>();
            headers.put("Accept", "application/vnd.opentimestamps.v1");
            headers.put("User-Agent", "java-opentimestamps");
            headers.put("Content-Type", "application/x-www-form-urlencoded");

            if (key != null) {
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
                throw new ExceededSizeException("Calendar response exceeded size limit");
            }

            StreamDeserializationContext ctx = new StreamDeserializationContext(body);
            return Timestamp.deserialize(ctx, digest);
        } catch (ExceededSizeException | DeserializationException e)
        {
            throw e;
        }
        catch (Exception e) {
            throw new UrlException(e.getMessage());
        }
    }

    /**
     * Get a timestamp for a given commitment.
     *
     * @param commitment The digest hash to send.
     * @return the Timestamp from the calendar server (with blockchain information if already written).
     * @throws ExceededSizeException       if response is too big.
     * @throws UrlException                if url is not reachable.
     * @throws CommitmentNotFoundException if commit is not found.
     * @throws DeserializationException    if the data is corrupt
     */
    public Timestamp getTimestamp(byte[] commitment) throws DeserializationException, ExceededSizeException, CommitmentNotFoundException, UrlException {
        try {
            Map<String, String> headers = new HashMap<>();
            headers.put("Accept", "application/vnd.opentimestamps.v1");
            headers.put("User-Agent", "java-opentimestamps");
            headers.put("Content-Type", "application/x-www-form-urlencoded");

            URL obj = new URL(url + "/timestamp/" + Utils.bytesToHex(commitment).toLowerCase());
            Request task = new Request(obj);
            task.setHeaders(headers);
            Response response = task.call();
            byte[] body = response.getBytes();
            if (body.length > 10000) {
                throw new ExceededSizeException("Calendar response exceeded size limit");
            }

            if (!response.isOk()) {
                throw new CommitmentNotFoundException("com.eternitywall.ots.Calendar response a status code != 200 which is: " + response.getStatus());
            }

            StreamDeserializationContext ctx = new StreamDeserializationContext(body);

            return Timestamp.deserialize(ctx, commitment);
        }
        catch (DeserializationException | ExceededSizeException | CommitmentNotFoundException e)
        {
            throw e;
        }
        catch (Exception e) {
            throw new UrlException(e.getMessage());
        }
    }
}
