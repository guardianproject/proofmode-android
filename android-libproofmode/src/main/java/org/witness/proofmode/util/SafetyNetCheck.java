package org.witness.proofmode.util;

import android.content.Context;
import android.support.annotation.NonNull;
import android.util.Log;

import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.GoogleApiAvailability;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.common.api.ResultCallback;
import com.google.android.gms.safetynet.SafetyNet;
import com.google.android.gms.safetynet.SafetyNetApi;
import com.google.android.gms.tasks.OnFailureListener;
import com.google.android.gms.tasks.OnSuccessListener;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.security.SecureRandom;
import java.util.Random;

/**
 * Sample that demonstrates the use of the SafetyNet Google Play Services API.
 * It handles option items (defined in menu/main.xml) to call the API and share its result via an
 * Intent.
 */
public class SafetyNetCheck
        implements GoogleApiClient.OnConnectionFailedListener {

    private static final String TAG = "SafetyNetCheck";

    private final Random mRandom = new SecureRandom();

    private static String sApiKey = null;

    public static void setApiKey (String apiKey)
    {
        sApiKey = apiKey;
    }

    public void sendSafetyNetRequest(Context context, String nonceData, OnSuccessListener<SafetyNetApi.AttestationResponse> successListener, OnFailureListener failureListener) {
        if (GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(context)
                == ConnectionResult.SUCCESS && sApiKey != null) {
            // The SafetyNet Attestation API is available.

            Log.d(TAG, "Sending SafetyNet API request.");

            byte[] nonce = getRequestNonce(nonceData);

            // Call the SafetyNet API asynchronously. The result is returned through the result callback.
            SafetyNet.getClient(context).attest(nonce, sApiKey).addOnSuccessListener(successListener).addOnFailureListener(failureListener);
        }
    }

    /**
     * Generates a 16-byte nonce with additional data.
     * The nonce should also include additional information, such as a user id or any other details
     * you wish to bind to this attestation. Here you can provide a String that is included in the
     * nonce after 24 random bytes. During verification, extract this data again and check it
     * against the request that was made with this nonce.
     */
    private byte[] getRequestNonce(String data) {
        ByteArrayOutputStream byteStream = new ByteArrayOutputStream();
        byte[] bytes = new byte[24];
        mRandom.nextBytes(bytes);
        try {
            byteStream.write(bytes);
            byteStream.write(data.getBytes());
        } catch (IOException e) {
            return null;
        }

        return byteStream.toByteArray();
    }


    @Override
    public void onConnectionFailed(@NonNull ConnectionResult connectionResult) {
        // Error occurred during connection to Google Play Services that could not be
        // automatically resolved.
        Log.e(TAG,
                "Error connecting to Google Play Services." + connectionResult.getErrorMessage());
    }

}