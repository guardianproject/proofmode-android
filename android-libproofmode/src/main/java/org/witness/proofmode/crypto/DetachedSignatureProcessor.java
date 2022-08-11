package org.witness.proofmode.crypto;


import org.bouncycastle.bcpg.ArmoredOutputStream;
import org.bouncycastle.bcpg.BCPGOutputStream;
import org.bouncycastle.openpgp.PGPCompressedData;
import org.bouncycastle.openpgp.PGPException;
import org.bouncycastle.openpgp.PGPObjectFactory;
import org.bouncycastle.openpgp.PGPPrivateKey;
import org.bouncycastle.openpgp.PGPPublicKey;
import org.bouncycastle.openpgp.PGPPublicKeyRingCollection;
import org.bouncycastle.openpgp.PGPSecretKey;
import org.bouncycastle.openpgp.PGPSignature;
import org.bouncycastle.openpgp.PGPSignatureGenerator;
import org.bouncycastle.openpgp.PGPSignatureList;
import org.bouncycastle.openpgp.PGPUtil;
import org.bouncycastle.openpgp.operator.bc.BcKeyFingerprintCalculator;
import org.bouncycastle.openpgp.operator.jcajce.JcaPGPContentSignerBuilder;
import org.bouncycastle.openpgp.operator.jcajce.JcaPGPContentVerifierBuilderProvider;
import org.bouncycastle.openpgp.operator.jcajce.JcePBESecretKeyDecryptorBuilder;
import org.witness.proofmode.ProofMode;

import java.io.BufferedInputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.GeneralSecurityException;

/**
 * A simple utility class that creates seperate signatures for files and verifies them.
 * <p>
 * To sign a file: DetachedSignatureProcessor -s [-a] fileName secretKey passPhrase.<br>
 * If -a is specified the output file will be "ascii-armored".
 * <p>
 * To decrypt: DetachedSignatureProcessor -v  fileName signatureFile publicKeyFile.
 * <p>
 * Note: this example will silently overwrite files.
 * It also expects that a single pass phrase
 * will have been used.
 */
public class DetachedSignatureProcessor
{
    public static boolean verifySignature(
            String fileName,
            String inputFileName,
            String keyFileName)
            throws GeneralSecurityException, IOException, PGPException
    {
        InputStream in = new BufferedInputStream(new FileInputStream(inputFileName));
        InputStream keyIn = new BufferedInputStream(new FileInputStream(keyFileName));

        boolean result = verifySignature(fileName, in, keyIn);

        keyIn.close();
        in.close();

        return result;
    }

    /*
     * verify the signature in in against the file fileName.
     */
    public static boolean verifySignature(
            String          fileName,
            InputStream     in,
            InputStream     keyIn)
            throws GeneralSecurityException, IOException, PGPException
    {
        in = PGPUtil.getDecoderStream(in);

        PGPObjectFactory pgpFact = new PGPObjectFactory(in, null);
        PGPSignatureList p3;

        Object    o = pgpFact.nextObject();
        if (o instanceof PGPCompressedData)
        {
            PGPCompressedData             c1 = (PGPCompressedData)o;

            pgpFact = new PGPObjectFactory(c1.getDataStream(), null);

            p3 = (PGPSignatureList)pgpFact.nextObject();
        }
        else
        {
            p3 = (PGPSignatureList)o;
        }

        PGPPublicKeyRingCollection pgpPubRingCollection = new PGPPublicKeyRingCollection(PGPUtil.getDecoderStream(keyIn),new BcKeyFingerprintCalculator());


        InputStream                 dIn = new BufferedInputStream(new FileInputStream(fileName));

        PGPSignature                sig = p3.get(0);
        PGPPublicKey                key = pgpPubRingCollection.getPublicKey(sig.getKeyID());

        sig.init(new JcaPGPContentVerifierBuilderProvider().setProvider("BC"), key);

        int ch;
        while ((ch = dIn.read()) >= 0)
        {
            sig.update((byte)ch);
        }

        dIn.close();

        if (sig.verify())
        {
            System.out.println("signature verified.");
            return true;
        }
        else
        {
            System.out.println("signature verification failed.");
            return false;
        }
    }

    public static void createSignature(
            PGPSecretKey             skey,
            InputStream          in,
            OutputStream    out,
            char[]          pass,
            boolean         armor)
            throws IOException, PGPException
    {

        if (armor)
        {
            out = new ArmoredOutputStream(out);
        }

        PGPPrivateKey            pgpPrivKey = skey.extractPrivateKey(new JcePBESecretKeyDecryptorBuilder().setProvider(ProofMode.getProvider()).build(pass));
        PGPSignatureGenerator sGen = new PGPSignatureGenerator(new JcaPGPContentSignerBuilder(skey.getPublicKey().getAlgorithm(), PGPUtil.SHA256).setProvider(ProofMode.getProvider()));

        sGen.init(PGPSignature.BINARY_DOCUMENT, pgpPrivKey);

        BCPGOutputStream         bOut = new BCPGOutputStream(out);

        int n = -1;
        byte[] buffer = new byte[2048];
        while ((n = in.read(buffer)) >= 0)
        {
            sGen.update(buffer,0,n);
        }

        in.close();

        sGen.generate().encode(bOut);

        if (armor)
        {
            out.close();
        }

        //this is for attached / inline sig

        /**
         PGPSignatureGenerator sGen = new PGPSignatureGenerator(new JcaPGPContentSignerBuilder(skey.getPublicKey().getAlgorithm(), PGPUtil.SHA256).setProvider("BC"));
         PGPSignatureSubpacketGenerator  spGen = new PGPSignatureSubpacketGenerator();

         sGen.init(PGPSignature.CANONICAL_TEXT_DOCUMENT, prKey);
         Iterator userIDs = skey.getPublicKey().getUserIDs();
         if (userIDs.hasNext()) {
         spGen.setSignerUserID(false, (String)userIDs.next());
         sGen.setHashedSubpackets(spGen.generate());
         }

         ArmoredOutputStream aos = new ArmoredOutputStream(out);
         aos.beginClearText(PGPUtil.SHA256);

         InputStream              fIn = new BufferedInputStream(in);

         int ch;
         while ((ch = fIn.read()) >= 0)
         {
         sGen.update((byte)ch);
         aos.write((byte)ch);
         }

         fIn.close();


         aos.endClearText();

         BCPGOutputStream bOut = new BCPGOutputStream(aos);
         sGen.generate().encode(bOut);

         aos.flush();
         aos.close();
         **/
    }


}