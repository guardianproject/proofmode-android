package org.witness.proofmode.crypto;

import android.content.Context;
import android.util.Log;

import org.spongycastle.bcpg.ArmoredInputStream;
import org.spongycastle.bcpg.ArmoredOutputStream;
import org.spongycastle.bcpg.HashAlgorithmTags;
import org.spongycastle.bcpg.SymmetricKeyAlgorithmTags;
import org.spongycastle.bcpg.sig.Features;
import org.spongycastle.bcpg.sig.KeyFlags;
import org.spongycastle.crypto.generators.RSAKeyPairGenerator;
import org.spongycastle.crypto.params.RSAKeyGenerationParameters;
import org.spongycastle.openpgp.PGPCompressedData;
import org.spongycastle.openpgp.PGPCompressedDataGenerator;
import org.spongycastle.openpgp.PGPEncryptedData;
import org.spongycastle.openpgp.PGPEncryptedDataGenerator;
import org.spongycastle.openpgp.PGPEncryptedDataList;
import org.spongycastle.openpgp.PGPException;
import org.spongycastle.openpgp.PGPKeyPair;
import org.spongycastle.openpgp.PGPKeyRingGenerator;
import org.spongycastle.openpgp.PGPLiteralData;
import org.spongycastle.openpgp.PGPLiteralDataGenerator;
import org.spongycastle.openpgp.PGPObjectFactory;
import org.spongycastle.openpgp.PGPPrivateKey;
import org.spongycastle.openpgp.PGPPublicKey;
import org.spongycastle.openpgp.PGPPublicKeyEncryptedData;
import org.spongycastle.openpgp.PGPPublicKeyRing;
import org.spongycastle.openpgp.PGPSecretKey;
import org.spongycastle.openpgp.PGPSecretKeyRing;
import org.spongycastle.openpgp.PGPSignature;
import org.spongycastle.openpgp.PGPSignatureSubpacketGenerator;
import org.spongycastle.openpgp.PGPUtil;
import org.spongycastle.openpgp.operator.PBESecretKeyDecryptor;
import org.spongycastle.openpgp.operator.PBESecretKeyEncryptor;
import org.spongycastle.openpgp.operator.PGPDigestCalculator;
import org.spongycastle.openpgp.operator.bc.BcKeyFingerprintCalculator;
import org.spongycastle.openpgp.operator.bc.BcPBESecretKeyDecryptorBuilder;
import org.spongycastle.openpgp.operator.bc.BcPBESecretKeyEncryptorBuilder;
import org.spongycastle.openpgp.operator.bc.BcPGPContentSignerBuilder;
import org.spongycastle.openpgp.operator.bc.BcPGPDigestCalculatorProvider;
import org.spongycastle.openpgp.operator.bc.BcPGPKeyPair;
import org.spongycastle.openpgp.operator.bc.BcPublicKeyDataDecryptorFactory;
import org.spongycastle.openpgp.operator.jcajce.JcaPGPContentSignerBuilder;
import org.spongycastle.openpgp.operator.jcajce.JcaPGPDigestCalculatorProviderBuilder;
import org.spongycastle.openpgp.operator.jcajce.JcePBESecretKeyEncryptorBuilder;
import org.spongycastle.openpgp.operator.jcajce.JcePGPDataEncryptorBuilder;
import org.spongycastle.openpgp.operator.jcajce.JcePublicKeyKeyEncryptionMethodGenerator;
import org.spongycastle.util.encoders.Hex;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
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
import java.security.InvalidKeyException;
import java.security.NoSuchProviderException;
import java.security.SecureRandom;
import java.security.SignatureException;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

public class PgpUtils {

    private static final String PROVIDER = "SC";
//    private static final String KEY_RING_ID = "asdf@asdf.com";

    private static PgpUtils mInstance;

    private PGPSecretKey pgpSec = null;
    private PGPSecretKeyRing skr = null;
    private PGPPublicKeyRing pkr = null;

    private final static String keyId = "noone@proofmode.witness.org";

    private final static String FILE_SECRET_KEY_RING = "pkr.asc";
    private final static String FILE_PUBLIC_KEY_RING = "pub.asc";

    public final static String DEFAULT_PASSWORD = "password"; //static string for local keystore
    private final static String URL_POST_KEY_ENDPOINT = "https://pgp.mit.edu/pks/add";

    public final static String URL_LOOKUP_ENDPOINT = "https://pgp.mit.edu/pks/lookup?op=get&search=0x";

    private PgpUtils ()
    {

    }

    public static synchronized PgpUtils getInstance (Context context)
    {
        return getInstance(context, DEFAULT_PASSWORD);
    }

    public static synchronized PgpUtils getInstance (Context context, String password)
    {
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

    public String decrypt(String encryptedText, String password) throws Exception {

        byte[] encrypted = encryptedText.getBytes();
        InputStream in = new ByteArrayInputStream(encrypted);
        in = PGPUtil.getDecoderStream(in);
        PGPObjectFactory pgpF = new PGPObjectFactory(in);
        PGPEncryptedDataList enc;
        Object o = pgpF.nextObject();
        if (o instanceof PGPEncryptedDataList) {
            enc = (PGPEncryptedDataList) o;
        } else {
            enc = (PGPEncryptedDataList) pgpF.nextObject();
        }
        PGPPrivateKey sKey = null;
        PGPPublicKeyEncryptedData pbe = null;
        while (sKey == null && enc.getEncryptedDataObjects().hasNext()) {
            pbe = (PGPPublicKeyEncryptedData)enc.getEncryptedDataObjects().next();
            sKey = getPrivateKey(skr, pbe.getKeyID(), password.toCharArray());
        }
        if (pbe != null) {
            InputStream clear = pbe.getDataStream(new BcPublicKeyDataDecryptorFactory(sKey));
            PGPObjectFactory pgpFact = new PGPObjectFactory(clear);
            PGPCompressedData cData = (PGPCompressedData) pgpFact.nextObject();
            pgpFact = new PGPObjectFactory(cData.getDataStream());
            PGPLiteralData ld = (PGPLiteralData) pgpFact.nextObject();
            InputStream unc = ld.getInputStream();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            int ch;
            while ((ch = unc.read()) >= 0) {
                out.write(ch);
            }
            byte[] returnBytes = out.toByteArray();
            out.close();
            return new String(returnBytes);
        }
        return null;
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
                                new SecureRandom()).setProvider(PROVIDER));
        if (encKey != null) {
            encGen.addMethod(new JcePublicKeyKeyEncryptionMethodGenerator(encKey).setProvider(PROVIDER));
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
        PGPKeyPair rsakp_sign = new BcPGPKeyPair(PGPPublicKey.RSA_SIGN, kpg.generateKeyPair(), new Date());
        PGPKeyPair rsakp_enc = new BcPGPKeyPair(PGPPublicKey.RSA_ENCRYPT, kpg.generateKeyPair(), new Date());
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



    public String getPublicKey () throws IOException {
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
        PGPSecretKey        secretKey = new PGPSecretKey(PGPSignature.DEFAULT_CERTIFICATION, keyPair, identity, sha1Calc, null, null, new JcaPGPContentSignerBuilder(keyPair.getPublicKey().getAlgorithm(), HashAlgorithmTags.SHA1), new JcePBESecretKeyEncryptorBuilder(PGPEncryptedData.CAST5, sha1Calc).setProvider("SC").build(passPhrase));

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

    public void createDetachedSignature (File media, File mediaSig, String password) throws Exception
    {
        DetachedSignatureProcessor.createSignature(pgpSec, new FileInputStream(media), new FileOutputStream(mediaSig), password.toCharArray(), true);

    }

    public void createDetachedSignature (InputStream media, OutputStream mediaSig, String password) throws Exception
    {
        DetachedSignatureProcessor.createSignature(pgpSec, media, mediaSig, password.toCharArray(), true);

    }


    public synchronized void initCrypto (Context context, String password)
    {
        if (pgpSec == null) {
            try {
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
                    final PGPKeyRingGenerator krgen = generateKeyRingGenerator(keyId, password.toCharArray());
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


            } catch (PGPException pgpe) {
                pgpe.printStackTrace();
            } catch (Exception pgpe) {
                pgpe.printStackTrace();

            }
        }
    }

    public void publishPublicKey () throws IOException
    {
        ByteArrayOutputStream baosPkr = new ByteArrayOutputStream();
        ArmoredOutputStream armoredStreamPkr = new ArmoredOutputStream(baosPkr);
        pkr.encode(armoredStreamPkr);
        armoredStreamPkr.close();
        final String pubKey = new String(baosPkr.toByteArray(), Charset.defaultCharset());

        new Thread () {

            public void run() {

                try {
                    HashMap<String,String> hmParams = new HashMap<String,String>();
                    hmParams.put("keytext",pubKey);
                    String queryString = createQueryStringForParameters(hmParams);

                    URL url = new URL(URL_POST_KEY_ENDPOINT);
                    HttpURLConnection client = null;
                    client = (HttpURLConnection) url.openConnection();
                    client.setRequestMethod("POST");
                    client.setFixedLengthStreamingMode(queryString.getBytes().length);
                    client.setRequestProperty("Content-Type",
                            "application/x-www-form-urlencoded");
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
                        Log.w("PGP","key did not upload: " + statusCode);
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


}