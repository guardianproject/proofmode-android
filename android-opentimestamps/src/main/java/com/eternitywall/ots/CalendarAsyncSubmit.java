package com.eternitywall.ots;

import com.eternitywall.http.Request;
import com.eternitywall.http.Response;
import org.bitcoinj.core.ECKey;

import java.net.URL;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Callable;

/**
 * For making async calls to a calendar server
 */
public class CalendarAsyncSubmit implements Callable<Optional<Timestamp>> {

    private String url;
    private byte[] digest;
    private BlockingQueue<Optional<Timestamp>> queue;
    private ECKey key;

    public CalendarAsyncSubmit(String url, byte[] digest) {
        this.url = url;
        this.digest = digest;
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

    public void setQueue(BlockingQueue<Optional<Timestamp>> queue) {
        this.queue = queue;
    }

    @Override
    public Optional<Timestamp> call() throws Exception {
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

        if (response.isOk()) {
            byte[] body = response.getBytes();
            StreamDeserializationContext ctx = new StreamDeserializationContext(body);
            Timestamp timestamp = Timestamp.deserialize(ctx, digest);
            Optional<Timestamp> of = Optional.of(timestamp);
            queue.add(of);

            return of;
        }

        queue.add(Optional.<Timestamp>absent());

        return Optional.absent();
    }
}
