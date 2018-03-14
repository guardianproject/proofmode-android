package com.eternitywall.ots;

import com.eternitywall.http.Request;
import com.eternitywall.http.Response;
import org.json.JSONObject;

import java.net.URL;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.*;
import java.util.logging.Logger;

/**
 * Created by luca on 07/03/2017.
 */
public class MultiInsight {
    private static Logger log = Logger.getLogger(MultiInsight.class.getName());

    private ExecutorService executor;
    private List<String> insightUrls;
    private BlockingQueue<Response> queueBlockHeader;
    private BlockingQueue<Response> queueBlockHash;


    public MultiInsight(){
        insightUrls = new ArrayList<>();
        insightUrls.add("https://search.bitaccess.co/insight-api");
        insightUrls.add("https://www.localbitcoinschain.com/api");
        insightUrls.add("https://insight.bitpay.com/api");
        insightUrls.add("https://finney.calendar.eternitywall.com/insight-api");
        queueBlockHeader = new ArrayBlockingQueue<>(insightUrls.size());
        queueBlockHash = new ArrayBlockingQueue<>(insightUrls.size());
        executor = Executors.newFixedThreadPool(insightUrls.size());
    }

    public ExecutorService getExecutor() {
        return executor;
    }

    /**
     * Retrieve the block information from the block hash.
     * @param hash Hash of the block.
     * @return the blockheader of the hash
     * @throws Exception desc
     */
    public BlockHeader block(String hash) throws Exception {
        for (String insightUrl: insightUrls) {
            URL url = new URL(insightUrl + "/block/" + hash);
            Request task = new Request(url);
            task.setQueue(queueBlockHeader);
            executor.submit(task);
        }
        Set<BlockHeader> results = new HashSet<>();

        for (int i = 0; i < insightUrls.size(); i++) {
            Response take = queueBlockHeader.take();
            if(take.isOk()) {
                JSONObject jsonObject = take.getJson();
                String merkleroot = jsonObject.getString("merkleroot");
                String time = String.valueOf(jsonObject.getInt("time"));
                BlockHeader blockHeader = new BlockHeader();
                blockHeader.setMerkleroot(merkleroot);
                blockHeader.setTime(time);
                blockHeader.setBlockHash(hash);
                log.info(take.getFromUrl() + " " + blockHeader);

                if (results.contains(blockHeader)) {
                    return blockHeader;
                }
                results.add(blockHeader);
            }
        }

        return null;
    }

    /**
     * Retrieve the block hash from the block height.
     * @param height Height of the block.
     * @return the hash of the block at height height
     * @throws Exception desc
     */
    public String blockHash(Integer height) throws Exception {
        for (String insightUrl: insightUrls) {
            URL url = new URL(insightUrl + "/block-index/" + height);
            Request task = new Request(url);
            task.setQueue(queueBlockHash);
            executor.submit(task);
        }
        Set<String> results = new HashSet<>();

        for (int i = 0; i < insightUrls.size(); i++) {
            Response take = queueBlockHash.take();
            if(take.isOk()) {
                JSONObject jsonObject = take.getJson();
                String blockHash = jsonObject.getString("blockHash");
                log.info(take.getFromUrl() + " " + blockHash);

                if (results.contains(blockHash)) {
                    return blockHash;
                }
                results.add(blockHash);
            }

        }

        return null;
    }

}
