package com.eternitywall.ots;

import com.eternitywall.http.Request;
import com.eternitywall.http.Response;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/**
 * Represents a (possibly local) Bitcoin node which we can ask for block hashes and headers
 */
public class BitcoinNode {

    private String authString;
    private String urlString;

    private static String RPCCONNECT = "rpcconnect";
    private static String RPCUSER = "rpcuser";
    private static String RPCPORT = "rpcport";
    private static String RPCPASSWORD = "rpcpassword";

    private BitcoinNode() {
    }

    public BitcoinNode(Properties bitcoinConf) {
        authString = String.valueOf(Base64Coder.encode(String.format("%s:%s", bitcoinConf.getProperty(RPCUSER), bitcoinConf.getProperty(RPCPASSWORD)).getBytes()));
        urlString = String.format("http://%s:%s", bitcoinConf.getProperty(RPCCONNECT), bitcoinConf.getProperty(RPCPORT));
    }

    public static Properties readBitcoinConf() throws Exception {
        String home = System.getProperty("user.home");
        List<String> list = Arrays.asList("/.bitcoin/bitcoin.conf", "\\AppData\\Roaming\\Bitcoin\\bitcoin.conf", "/Library/Application Support/Bitcoin/bitcoin.conf");

        for (String dir : list) {
            Properties prop = new Properties();
            InputStream input = null;

            try {
                input = new FileInputStream(home + dir);

                prop.load(input);

                // If we have a RPC user and password, make sure we set RPCCONNECT and RPCPORT, if missing
                if (prop.getProperty(RPCUSER) != null && prop.getProperty(RPCPASSWORD) != null) {
                    if (prop.getProperty(RPCCONNECT) == null) {
                        prop.setProperty(RPCCONNECT, "127.0.0.1");
                    }

                    if (prop.getProperty(RPCPORT) == null) {
                        prop.setProperty(RPCPORT, "8332");
                    }

                    return prop;
                }
            } catch (IOException ex) {
                // This is expected for all the paths to bitcoin.conf that doesn't exist on this particular machine
            } finally {
                if (input != null) {
                    try {
                        input.close();
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                }
            }
        }

        throw new Exception();    // TODO: Add this message: "No bitcoin.conf file found in any of these paths: " + list
    }

    public JSONObject getInfo() throws Exception {
        JSONObject json = new JSONObject();
        json.put("id", "java");
        json.put("method", "getinfo");

        return callRPC(json);
    }

    public BlockHeader getBlockHeader(Integer height) throws Exception {
        return getBlockHeader(getBlockHash(height));
    }

    public BlockHeader getBlockHeader(String hash) throws Exception {
        if (hash == null) {
            return null;      // TODO: I think this will result in strange failures later on. Throw instead?
        }

        JSONObject json = new JSONObject();
        json.put("id", "java");
        json.put("method", "getblockheader");
        JSONArray array = new JSONArray();
        array.put(hash);
        json.put("params", array);
        JSONObject jsonObject = callRPC(json);
        BlockHeader blockHeader = new BlockHeader();
        JSONObject result = jsonObject.getJSONObject("result");
        blockHeader.setMerkleroot(result.getString("merkleroot"));
        blockHeader.setBlockHash(hash);
        blockHeader.setTime(String.valueOf(result.getInt("time")));

        return blockHeader;
    }

    public String getBlockHash(Integer height) throws Exception {
        JSONObject json = new JSONObject();
        json.put("id", "java");
        json.put("method", "getblockhash");
        JSONArray array = new JSONArray();
        array.put(height);
        json.put("params", array);
        JSONObject jsonObject = callRPC(json);

        if (jsonObject == null) {
            return null;
        }

        return jsonObject.getString("result");
    }

    private JSONObject callRPC(JSONObject query) throws Exception {
        String s = query.toString();
        URL url = new URL(urlString);
        Request request = new Request(url);
        Map<String, String> headers = new HashMap<>();
        headers.put("Authorization", "Basic " + authString);
        request.setHeaders(headers);
        request.setData(s.getBytes());
        Response response = request.call();

        if (response == null) {
            throw new Exception();
        }

        return new JSONObject(response.getString());
    }
}
