package org.witness.proofmode.notarization;

import android.content.Context;
import android.net.Proxy;
import android.os.AsyncTask;
import android.text.TextUtils;
import android.util.Log;
import android.webkit.MimeTypeMap;

import org.json.JSONException;
import org.json.JSONObject;
import org.w3c.dom.Text;
import org.witness.proofmode.R;
import org.witness.proofmode.crypto.HashUtils;

import java.io.File;
import java.io.IOError;
import java.io.IOException;
import java.net.URL;
import java.util.List;

import okhttp3.Authenticator;
import okhttp3.Challenge;
import okhttp3.Credentials;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okhttp3.Route;

import static org.witness.proofmode.ProofModeApp.TAG;

/**
 * Created by n8fr8 on 5/31/17.
 */

public class TimeBeatNotarizationProvider implements NotarizationProvider {

    private String mBaseEndpoint = null;
    private Context mContext = null;

    private final static String PATH_NOTARIZE = "/s/timeStamp/reg";

    public TimeBeatNotarizationProvider (Context context)
    {
        mContext = context;
        mBaseEndpoint = "https://" + context.getString(R.string.enigio_site_url);
    }

    @Override
    public void notarize(String comment, File fileMedia, NotarizationListener listener) {


        new NotarizationTask(comment, listener).execute(fileMedia);


    }

    @Override
    public String getProofURI(String hash) {
        return null;
    }



    /**
     Parameter List:
     size – number of bytes
     fileName – name of the file
     sha256 – hex encoding of the SHA-256 hash
     c – comment (optional)
     userId – used for identification via web login
     Responses:
     HTTP 200 + timestamp if all ok
     JSON response with
     s: status – exists: true/false
     v: value – the timestamp in milliseconds
     m: messag
     **/
    private String doNotarizationRequest (long numOfBytes, String fileName, String mimeType, String sha256sum, String comment) throws IOException
    {
        String result = null;

        OkHttpClient client = new OkHttpClient.Builder().authenticator(new Authenticator() {
            @Override
            public Request authenticate(Route route, Response response) throws IOException {
                String credential = Credentials.basic(mContext.getString(R.string.enigio_username), mContext.getString(R.string.enigio_password));
                return response.request().newBuilder()
                        .header("Authorization", credential)
                        .build();
            }
        }).build();

        HttpUrl.Builder notarizeUrl = HttpUrl.parse(mBaseEndpoint + PATH_NOTARIZE).newBuilder();
        notarizeUrl.addQueryParameter("size",numOfBytes+"");
        notarizeUrl.addQueryParameter("fileName",fileName);
        notarizeUrl.addQueryParameter("sha256",sha256sum);
        notarizeUrl.addQueryParameter("mime",mimeType);

        if (!TextUtils.isEmpty(comment))
            notarizeUrl.addQueryParameter("c",comment);

        notarizeUrl.addQueryParameter("path","demo");
        notarizeUrl.addQueryParameter("userId",mContext.getString(R.string.enigio_email));

        Log.d(TAG,"timebeat request: " + notarizeUrl.build().toString());

        // Create request for remote resource.
        Request request = new Request.Builder()
                .url(notarizeUrl.build())
                .build();

        // Execute the request and retrieve the response.
        Response response = client.newCall(request).execute();

        // Deserialize HTTP response to concrete type.
        ResponseBody body = response.body();

        try {
            String tsResponse = body.string();

            Log.d(TAG, "timebeat response: " + tsResponse);
            result = parseResponse(tsResponse);

        }
        catch (JSONException e)
        {
            Log.w(TAG, "error parsing timebeat response",e);

        }
        body.close();

        return result;
    }

    private class NotarizationTask extends AsyncTask<File, Integer, Long> {

        private String mComment = null;
        private NotarizationListener mListener = null;

        public NotarizationTask(String comment, NotarizationListener listener)
        {
            super();
            mComment = comment;
            mListener = listener;
        }

        protected Long doInBackground(File... fileMedias) {

            for (int i = 0; i < fileMedias.length; i++) {

                File fileMedia = fileMedias[0];
                String sha256hash = HashUtils.getSHA256FromFileContent(fileMedia);

                String mimeType = MimeTypeMap.getSingleton().getMimeTypeFromExtension(MimeTypeMap.getFileExtensionFromUrl(fileMedia.getPath()));
                if (TextUtils.isEmpty(mimeType)) {
                    if (fileMedia.getName().endsWith("jpg"))
                        mimeType = "image/jpeg";
                    else if (fileMedia.getName().endsWith("mp4"))
                        mimeType = "video/mp4";
                    else
                        mimeType = "application/octet-stream";
                }

                try {
                    String response = doNotarizationRequest(fileMedia.length(), fileMedia.getName(), mimeType, sha256hash, mComment);
                    Log.i(TAG,"got notarization response: " + response);
                    mListener.notarizationSuccessful(response);
                } catch (Exception e) {
                    Log.e(TAG, "Error notarizing via timebeat", e);
                    mListener.notarizationFailed(-1,e.getMessage());
                }
            }

            long totalSize = 0;
            return totalSize;
        }


        protected void onPostExecute(String result) {
        }
    }


    private String parseResponse (String notarizeResponse) throws JSONException
    {

        /**
         * Examples of response:
         {"s":true,"v":1461658852242,"m":""}
         or
         {"s":false,"v":null,"m":"Already registered:
         '57c810355df8f8a332278170d8ecb3e604489a23e6b408b2b17db0c18d3f0421'.@
         2016-04-26 07:54:55 UTC"}

         s - true/false depending on whether the timestamp has succeeded
         m - message
         v - time in milliseconds when the timestamp has been registered
         */

        JSONObject jsResponse = new JSONObject(notarizeResponse);

        boolean success = jsResponse.getBoolean("s");
        String message= jsResponse.getString("m");

        if (success)
        {
            String timestamp = jsResponse.getLong("v")+"";
            return timestamp;
        }
        else
            return message;

    }

    private String checkTimestamp (String timestamp)
    {
        /**
         * !
         Call to aquire proof of timestamp:
         https://demo.enigio.com/proof/8fb4ade01fd7b2bcec0d12f80c158c14752ca51f733c85396e7dca50d4542e8e?
         channel=bitcoin
         Parameter List:
         channel – name of channel (optional)
         Responses:
         HTTP 200 + JSON with complete chain of proof if published
         HTTP 204 No proof found because no publication has done yet or not on the given channel
         HTTP 400 Unexpected error
         HTTP 404 No timestamp for given hash
         Demo
         Publication/channels:
         -
         Wordpress:  12/day
         -
         Google Groups:  12/day
         */

        return null;
    }


}
