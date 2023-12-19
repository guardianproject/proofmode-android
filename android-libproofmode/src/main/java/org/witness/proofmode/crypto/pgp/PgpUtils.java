package org.witness.proofmode.crypto.pgp;

import android.content.Context;
import android.util.Base64;
import android.util.Base64OutputStream;
import android.util.Log;


import org.bouncycastle.bcpg.ArmoredInputStream;
import org.bouncycastle.bcpg.ArmoredOutputStream;
import org.bouncycastle.bcpg.HashAlgorithmTags;
import org.bouncycastle.bcpg.SymmetricKeyAlgorithmTags;
import org.bouncycastle.bcpg.sig.Features;
import org.bouncycastle.bcpg.sig.KeyFlags;
import org.bouncycastle.crypto.generators.RSAKeyPairGenerator;
import org.bouncycastle.crypto.params.RSAKeyGenerationParameters;
import org.bouncycastle.openpgp.PGPCompressedDataGenerator;
import org.bouncycastle.openpgp.PGPEncryptedData;
import org.bouncycastle.openpgp.PGPEncryptedDataGenerator;
import org.bouncycastle.openpgp.PGPException;
import org.bouncycastle.openpgp.PGPKeyPair;
import org.bouncycastle.openpgp.PGPKeyRingGenerator;
import org.bouncycastle.openpgp.PGPLiteralData;
import org.bouncycastle.openpgp.PGPLiteralDataGenerator;
import org.bouncycastle.openpgp.PGPPrivateKey;
import org.bouncycastle.openpgp.PGPPublicKey;
import org.bouncycastle.openpgp.PGPPublicKeyRing;
import org.bouncycastle.openpgp.PGPSecretKey;
import org.bouncycastle.openpgp.PGPSecretKeyRing;
import org.bouncycastle.openpgp.PGPSignature;
import org.bouncycastle.openpgp.PGPSignatureSubpacketGenerator;
import org.bouncycastle.openpgp.PGPUtil;
import org.bouncycastle.openpgp.jcajce.JcaPGPPublicKeyRingCollection;
import org.bouncycastle.openpgp.operator.PBESecretKeyDecryptor;
import org.bouncycastle.openpgp.operator.PBESecretKeyEncryptor;
import org.bouncycastle.openpgp.operator.PGPDigestCalculator;
import org.bouncycastle.openpgp.operator.bc.BcKeyFingerprintCalculator;
import org.bouncycastle.openpgp.operator.bc.BcPBESecretKeyDecryptorBuilder;
import org.bouncycastle.openpgp.operator.bc.BcPBESecretKeyEncryptorBuilder;
import org.bouncycastle.openpgp.operator.bc.BcPGPContentSignerBuilder;
import org.bouncycastle.openpgp.operator.bc.BcPGPDigestCalculatorProvider;
import org.bouncycastle.openpgp.operator.bc.BcPGPKeyPair;
import org.bouncycastle.openpgp.operator.jcajce.JcaPGPContentSignerBuilder;
import org.bouncycastle.openpgp.operator.jcajce.JcaPGPDigestCalculatorProviderBuilder;
import org.bouncycastle.openpgp.operator.jcajce.JcePBESecretKeyEncryptorBuilder;
import org.bouncycastle.openpgp.operator.jcajce.JcePGPDataEncryptorBuilder;
import org.bouncycastle.openpgp.operator.jcajce.JcePublicKeyKeyEncryptionMethodGenerator;
import org.bouncycastle.util.encoders.Hex;
import org.bouncycastle.util.io.Streams;
import org.witness.proofmode.ProofMode;

import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.math.BigInteger;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.security.InvalidKeyException;
import java.security.Key;
import java.security.NoSuchProviderException;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.SignatureException;
import java.util.Date;
import java.util.Iterator;
import java.util.Map;

import javax.crypto.Cipher;


public class PgpUtils {

    private static PgpUtils mInstance;

    private PGPSecretKey pgpSec = null;
    private PGPSecretKeyRing skr = null;
    private PGPPublicKeyRing pkr = null;

    private static String keyEmail = "noone@proofmode.witness.org";

    private final static String FILE_SECRET_KEY_RING = "pkr.asc";
    private final static String FILE_PUBLIC_KEY_RING = "pub.asc";

    private final static String URL_POST_KEY_ENDPOINT = "https://keys.openpgp.org/vks/v1/upload";

    public final static String URL_LOOKUP_ENDPOINT = "https://keys.openpgp.org/search?q=0x";

    private PgpUtils ()
    {

    }

    /**
    public static synchronized PgpUtils getInstance (Context context) throws PGPException, IOException {
        return getInstance(context, DEFAULT_PASSWORD);
    }
    **/

    public static synchronized PgpUtils getInstance (Context context, String password) throws PGPException, IOException {
        if (mInstance == null)
        {
            mInstance = new PgpUtils();
            mInstance.initCrypto(context, password);
        }

        return mInstance;

    }

    public String getPublicKeyFingerprint ()
    {
        PGPPublicKey key = pkr.getPublicKey();
        String fullKey = new String(Hex.encode(key.getFingerprint()));
        return fullKey.substring(fullKey.length()-16);
    }

    public PGPPublicKey getPublicKey () {
        return pkr.getPublicKey();
    }

    private static PGPPublicKey getPublicKey(PGPPublicKeyRing publicKeyRing) {
        Iterator<?> kIt = publicKeyRing.getPublicKeys();
        while (kIt.hasNext()) {
            PGPPublicKey k = (PGPPublicKey) kIt.next();
            if (k.isEncryptionKey()) {
                return k;
            }
        }
        return null;
    }

    private static PGPPrivateKey getPrivateKey(PGPSecretKeyRing keyRing, long keyID, char[] pass) throws PGPException {
        PGPSecretKey secretKey = keyRing.getSecretKey(keyID);
        PBESecretKeyDecryptor decryptor = new BcPBESecretKeyDecryptorBuilder(new BcPGPDigestCalculatorProvider()).build(pass);
        return secretKey.extractPrivateKey(decryptor);
    }

    public void encrypt(InputStream inClear, long dataLen, OutputStream encOut) throws IOException, PGPException {

        PGPPublicKey encKey = getPublicKey(pkr);
        OutputStream out = new ArmoredOutputStream(encOut);
        ByteArrayOutputStream bOut = new ByteArrayOutputStream();
        PGPCompressedDataGenerator comData = new PGPCompressedDataGenerator(PGPCompressedDataGenerator.ZIP);
        OutputStream cos = comData.open(bOut);
        PGPLiteralDataGenerator lData = new PGPLiteralDataGenerator();
        OutputStream pOut = lData.open(cos, PGPLiteralData.BINARY, PGPLiteralData.CONSOLE, dataLen, new Date());

        for (int i = 0; i < dataLen; i++)
            pOut.write(inClear.read());

        lData.close();
        comData.close();
        PGPEncryptedDataGenerator encGen =
                new PGPEncryptedDataGenerator(
                        new JcePGPDataEncryptorBuilder(PGPEncryptedData.AES_256).setWithIntegrityPacket(true).setSecureRandom(
                                new SecureRandom()).setProvider(ProofMode.getProvider()));
        if (encKey != null) {
            encGen.addMethod(new JcePublicKeyKeyEncryptionMethodGenerator(encKey).setProvider(ProofMode.getProvider()));
            byte[] bytes = bOut.toByteArray();
            OutputStream cOut = encGen.open(out, bytes.length);
            cOut.write(bytes);
            cOut.close();
        }
        out.close();
    }

    public String encrypt(String msgText) throws IOException, PGPException {
        byte[] clearData = msgText.getBytes();
        PGPPublicKey encKey = getPublicKey(pkr);
        ByteArrayOutputStream encOut = new ByteArrayOutputStream();
        OutputStream out = new ArmoredOutputStream(encOut);
        ByteArrayOutputStream bOut = new ByteArrayOutputStream();
        PGPCompressedDataGenerator comData = new PGPCompressedDataGenerator(PGPCompressedDataGenerator.ZIP);
        OutputStream cos = comData.open(bOut);
        PGPLiteralDataGenerator lData = new PGPLiteralDataGenerator();
        OutputStream pOut = lData.open(cos, PGPLiteralData.BINARY, PGPLiteralData.CONSOLE, clearData.length, new Date());
        pOut.write(clearData);
        lData.close();
        comData.close();
        PGPEncryptedDataGenerator encGen =
                new PGPEncryptedDataGenerator(
                        new JcePGPDataEncryptorBuilder(PGPEncryptedData.AES_256).setWithIntegrityPacket(true).setSecureRandom(
                                new SecureRandom()).setProvider(ProofMode.getProvider()));
        if (encKey != null) {
            encGen.addMethod(new JcePublicKeyKeyEncryptionMethodGenerator(encKey).setProvider(ProofMode.getProvider()));
            byte[] bytes = bOut.toByteArray();
            OutputStream cOut = encGen.open(out, bytes.length);
            cOut.write(bytes);
            cOut.close();
        }
        out.close();
        return new String(encOut.toByteArray());
    }

    public final static PGPKeyRingGenerator generateKeyRingGenerator (String keyId, char[] pass) throws PGPException{
        RSAKeyPairGenerator kpg = new RSAKeyPairGenerator();
        kpg.init(new RSAKeyGenerationParameters(BigInteger.valueOf(0x10001), new SecureRandom(), 4096, 12));
        PGPKeyPair rsakp_sign = new BcPGPKeyPair(PGPPublicKey.RSA_GENERAL, kpg.generateKeyPair(), new Date());
        PGPKeyPair rsakp_enc = new BcPGPKeyPair(PGPPublicKey.RSA_GENERAL, kpg.generateKeyPair(), new Date());
        PGPSignatureSubpacketGenerator signhashgen = new PGPSignatureSubpacketGenerator();
        signhashgen.setKeyFlags(false, KeyFlags.SIGN_DATA|KeyFlags.CERTIFY_OTHER|KeyFlags.SHARED);
        signhashgen.setPreferredSymmetricAlgorithms(false, new int[]{SymmetricKeyAlgorithmTags.AES_256, SymmetricKeyAlgorithmTags.AES_192, SymmetricKeyAlgorithmTags.AES_128});
        signhashgen.setPreferredHashAlgorithms(false, new int[]{HashAlgorithmTags.SHA256, HashAlgorithmTags.SHA1, HashAlgorithmTags.SHA384, HashAlgorithmTags.SHA512, HashAlgorithmTags.SHA224});
        signhashgen.setFeature(false, Features.FEATURE_MODIFICATION_DETECTION);
        PGPSignatureSubpacketGenerator enchashgen = new PGPSignatureSubpacketGenerator();
        enchashgen.setKeyFlags(false, KeyFlags.ENCRYPT_COMMS | KeyFlags.ENCRYPT_STORAGE);
        PGPDigestCalculator sha1Calc = new BcPGPDigestCalculatorProvider().get(HashAlgorithmTags.SHA1);
        PGPDigestCalculator sha256Calc = new BcPGPDigestCalculatorProvider().get(HashAlgorithmTags.SHA256);
        PBESecretKeyEncryptor pske = (new BcPBESecretKeyEncryptorBuilder(PGPEncryptedData.AES_256, sha256Calc, 0xc0)).build(pass);
        PGPKeyRingGenerator keyRingGen = new PGPKeyRingGenerator (PGPSignature.POSITIVE_CERTIFICATION, rsakp_sign,
                keyId, sha1Calc, signhashgen.generate(), null, new BcPGPContentSignerBuilder(rsakp_sign.getPublicKey().getAlgorithm(),
                HashAlgorithmTags.SHA1), pske);
        keyRingGen.addSubKey(rsakp_enc, enchashgen.generate(), null);
        return keyRingGen;
    }



    public String getPublicKeyString () throws IOException {
        ByteArrayOutputStream baosPkr = new ByteArrayOutputStream();
        ArmoredOutputStream armoredStreamPkr = new ArmoredOutputStream(baosPkr);
        pkr.encode(armoredStreamPkr);
        armoredStreamPkr.close();

        return new String(baosPkr.toByteArray(), Charset.defaultCharset());
    }

    public final static String genPGPPrivKey (PGPKeyRingGenerator krgen) throws IOException {
            // String pgpPublicKey = PgpUtils.genPGPPublicKey(krgen);
            //DetachedSignatureProcessor.createSignature(pgpSecretKey, )

        ByteArrayOutputStream baosPriv = new ByteArrayOutputStream ();
        PGPSecretKeyRing skr = krgen.generateSecretKeyRing();
        ArmoredOutputStream armoredStreamPriv = new ArmoredOutputStream(baosPriv);
        skr.encode(armoredStreamPriv);
        armoredStreamPriv.close();
        return new String(baosPriv.toByteArray(), Charset.defaultCharset());
    }

    private static void exportKeyPair(
            OutputStream    secretOut,
            OutputStream    publicOut,
            PGPPublicKey publicKey,
            PGPPrivateKey privateKey,
            String          identity,
            char[]          passPhrase,
            boolean         armor)
            throws IOException, InvalidKeyException, NoSuchProviderException, SignatureException, PGPException
    {
        if (armor)
        {
            secretOut = new ArmoredOutputStream(secretOut);
        }

        PGPDigestCalculator sha1Calc = new JcaPGPDigestCalculatorProviderBuilder().build().get(HashAlgorithmTags.SHA1);
        PGPKeyPair          keyPair = new PGPKeyPair(publicKey, privateKey);
        PGPSecretKey        secretKey = new PGPSecretKey(PGPSignature.DEFAULT_CERTIFICATION, keyPair, identity, sha1Calc, null, null, new JcaPGPContentSignerBuilder(keyPair.getPublicKey().getAlgorithm(), HashAlgorithmTags.SHA1), new JcePBESecretKeyEncryptorBuilder(PGPEncryptedData.CAST5, sha1Calc).setProvider(ProofMode.getProvider()).build(passPhrase));

        secretKey.encode(secretOut);

        secretOut.close();

        if (armor)
        {
            publicOut = new ArmoredOutputStream(publicOut);
        }

        PGPPublicKey    key = secretKey.getPublicKey();

        key.encode(publicOut);

        publicOut.close();
    }

    public void createDetachedSignature (File media, File mediaSig, String password, boolean armor) throws IOException, PGPException {
        createDetachedSignature(new FileInputStream(media),new FileOutputStream(mediaSig), password, armor);

    }

    public void createDetachedSignature (InputStream is, OutputStream mediaSig, String password, boolean armor) throws PGPException, IOException {
        DetachedSignatureProcessor.createSignature(pgpSec, new DataInputStream(is), mediaSig, password.toCharArray(), armor);

    }

    public boolean verifyDetachedSignature (InputStream fileStream, InputStream sigStream, PGPPublicKey pubKey) throws Exception
    {
        return DetachedSignatureProcessor.verifySignature(fileStream, sigStream, pubKey);
    }

    public static boolean keyRingExists (Context context)
    {
        File fileSecKeyRing = new File(context.getFilesDir(),FILE_SECRET_KEY_RING);
        File filePubKeyRing = new File(context.getFilesDir(),FILE_PUBLIC_KEY_RING);

        return fileSecKeyRing.exists() && filePubKeyRing.exists();
    }

    public void resetCrypto (Context context) {
        File fileSecKeyRing = new File(context.getFilesDir(),FILE_SECRET_KEY_RING);
        File filePubKeyRing = new File(context.getFilesDir(),FILE_PUBLIC_KEY_RING);
        fileSecKeyRing.delete();
        filePubKeyRing.delete();
        pgpSec = null;
    }

    public static void setKeyid (String email) {
        keyEmail = email;
    }

    public synchronized void initCrypto (Context context, String password) throws IOException, PGPException {
        if (pgpSec == null) {

                File fileSecKeyRing = new File(context.getFilesDir(),FILE_SECRET_KEY_RING);
                File filePubKeyRing = new File(context.getFilesDir(),FILE_PUBLIC_KEY_RING);

                if (fileSecKeyRing.exists())
                {
                    ArmoredInputStream sin = new ArmoredInputStream(new FileInputStream(fileSecKeyRing));
                    skr = new PGPSecretKeyRing(sin,new BcKeyFingerprintCalculator());
                    sin.close();

                    sin = new ArmoredInputStream(new FileInputStream(filePubKeyRing));
                    pkr = new PGPPublicKeyRing(sin, new BcKeyFingerprintCalculator());
                    sin.close();
                }
                else {

                    if (password.isEmpty())
                        throw new IOException("Empty PGP Key password not allowed for key generation");

                    final PGPKeyRingGenerator krgen = generateKeyRingGenerator(keyEmail, password.toCharArray());
                    skr = krgen.generateSecretKeyRing();

                    ArmoredOutputStream sout = new ArmoredOutputStream((new FileOutputStream(fileSecKeyRing)));
                    skr.encode(sout);
                    sout.close();

                    pkr = krgen.generatePublicKeyRing();

                    sout = new ArmoredOutputStream((new FileOutputStream(filePubKeyRing)));
                    pkr.encode(sout);
                    sout.close();

                }

                pgpSec = skr.getSecretKey();


        }
    }

    public void publishPublicKey () throws IOException
    {
        ByteArrayOutputStream baosPkr = new ByteArrayOutputStream();
        Base64OutputStream bos = new Base64OutputStream(baosPkr, Base64.DEFAULT);
        pkr.encode(bos);
        bos.close();

        final String pubKey = new String(baosPkr.toByteArray(), Charset.forName("UTF-8"));

        new Thread () {

            public void run() {

                try {

                    String queryString = "{\"keytext\":\"" + pubKey + "\"}";

                    URL url = new URL(URL_POST_KEY_ENDPOINT);
                    HttpURLConnection client = null;
                    client = (HttpURLConnection) url.openConnection();
                    client.setRequestMethod("POST");
                    client.setRequestProperty("Content-Type",
                            "application/json");
                    client.setDoOutput(true);
                    client.setDoInput(true);
                    client.setReadTimeout(20000);
                    client.setConnectTimeout(30000);

                    PrintWriter out = new PrintWriter(client.getOutputStream());
                    out.print(queryString);
                    out.close();


                    // handle issues
                    int statusCode = client.getResponseCode();
                    if (statusCode != HttpURLConnection.HTTP_OK) {
                        // throw some exception
                        Log.w("PGP","key did not upload: " + statusCode + " = " + client.getResponseMessage());
                    }
                    else
                    {
                        Log.w("PGP", "Published key: " + client.getResponseMessage());


                    }


                    client.disconnect();
                }
                catch (IOException ioe)
                {
                    ioe.printStackTrace();
                }
            }
        }.start();;
    }

    private static final char PARAMETER_DELIMITER = '&';
    private static final char PARAMETER_EQUALS_CHAR = '=';
    public static String createQueryStringForParameters(Map<String, String> parameters) {
        StringBuilder parametersAsQueryString = new StringBuilder();
        if (parameters != null) {
            boolean firstParameter = true;

            for (String parameterName : parameters.keySet()) {
                if (!firstParameter) {
                    parametersAsQueryString.append(PARAMETER_DELIMITER);
                }

                parametersAsQueryString.append(parameterName)
                        .append(PARAMETER_EQUALS_CHAR)
                        .append(URLEncoder.encode(
                                parameters.get(parameterName)));

                firstParameter = false;
            }
        }
        return parametersAsQueryString.toString();
    }

    public static boolean verifyStream () throws IOException {

        return true;

    }

    public static PGPPublicKey getPublicKey(InputStream encodedKey) throws IOException, PGPException, Exception {

        InputStream decodedKey = PGPUtil.getDecoderStream(encodedKey);

        JcaPGPPublicKeyRingCollection pgpPub = new JcaPGPPublicKeyRingCollection(decodedKey);
        decodedKey.close();

        PGPPublicKey key = null;
        Iterator<PGPPublicKeyRing> rIt = pgpPub.getKeyRings();
        while (key == null && rIt.hasNext()) {
            PGPPublicKeyRing kRing = rIt.next();
            Iterator<PGPPublicKey> kIt = kRing.getPublicKeys();

            while (key == null && kIt.hasNext()) {
                PGPPublicKey k = kIt.next();

                if (k.isEncryptionKey()) {
                    key = k;
                }
            }
        }
        if (key == null) {
            throw new Exception("Can't find key");
        }
        return key;
    }


}