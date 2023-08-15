package org.witness.proofmode.crypto.pgp;

import android.util.Base64;

import java.io.InputStream;
import java.security.AlgorithmParameters;
import java.security.SecureRandom;
import java.security.spec.KeySpec;
import java.util.HashMap;
import java.util.Map;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

public class AES {
    private static SecureRandom random;
    public static byte[] initIV ()
    {
        byte[] IV = new byte[16];
        random = new SecureRandom();
        random.nextBytes(IV);
        return IV;
    }

    public final static String CIPHER = "AES/GCM/NoPadding";
    public final static String KEY_SPEC = "AES";
    public final static String SECRET_KEY_FACTORY = "PBKDF2WithHmacSHA1";

    public static byte[] decrypt(String password, byte[] iv, InputStream is, byte[] salt) throws Exception {

        initIV();

        SecretKeyFactory factory = SecretKeyFactory.getInstance(SECRET_KEY_FACTORY);
        KeySpec spec = new PBEKeySpec(password.toCharArray(), salt, 65536, 256);
        SecretKey tmp = factory.generateSecret(spec);
        SecretKey secret_key = new SecretKeySpec(tmp.getEncoded(), KEY_SPEC);

        Cipher cipher = Cipher.getInstance(CIPHER);
        IvParameterSpec ivSpec = new IvParameterSpec(iv);
        cipher.init(Cipher.DECRYPT_MODE, secret_key, ivSpec);

        int i = -1;
        byte[] buffer = new byte[1024];
        while ((i = is.read(buffer))!=-1)
            cipher.update(buffer);

        byte[] output = cipher.doFinal();

        return output;
    }

    public static Map<String, byte[]> encrypt(String password, InputStream is, byte[] salt) throws Exception {

        initIV();

        Map<String, byte[]> pack = null;

        SecretKeyFactory factory = SecretKeyFactory.getInstance(SECRET_KEY_FACTORY);
        KeySpec spec = new PBEKeySpec(password.toCharArray(), salt, 65536, 256);
        SecretKey tmp = factory.generateSecret(spec);
        SecretKey secret_key = new SecretKeySpec(tmp.getEncoded(), KEY_SPEC);

        Cipher cipher = Cipher.getInstance(CIPHER);

        // TODO: follow up (https://android-developers.blogspot.com/2013/08/some-securerandom-thoughts.html)
        cipher.init(Cipher.ENCRYPT_MODE, secret_key);

        AlgorithmParameters params = cipher.getParameters();
        GCMParameterSpec ivSpec = params.getParameterSpec(GCMParameterSpec.class);
        String iv = Base64.encodeToString(ivSpec.getIV(), Base64.DEFAULT);

        int i = -1;
        byte[] buffer = new byte[1024];
        while ((i = is.read(buffer))!=-1)
            cipher.update(buffer);

        byte[] output = cipher.doFinal();

        pack = new HashMap<>();
        pack.put(iv, output);

        return pack;
    }
}
