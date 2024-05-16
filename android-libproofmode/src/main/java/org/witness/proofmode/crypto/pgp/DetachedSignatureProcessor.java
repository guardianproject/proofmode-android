package org.witness.proofmode.crypto.pgp;


import org.bouncycastle.bcpg.ArmoredInputStream;
import org.bouncycastle.bcpg.ArmoredOutputStream;
import org.bouncycastle.bcpg.BCPGOutputStream;
import org.bouncycastle.bcpg.HashAlgorithmTags;
import org.bouncycastle.bcpg.SymmetricKeyAlgorithmTags;
import org.bouncycastle.bcpg.sig.Features;
import org.bouncycastle.bcpg.sig.KeyFlags;
import org.bouncycastle.crypto.generators.RSAKeyPairGenerator;
import org.bouncycastle.crypto.params.RSAKeyGenerationParameters;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.openpgp.PGPCompressedData;
import org.bouncycastle.openpgp.PGPCompressedDataGenerator;
import org.bouncycastle.openpgp.PGPEncryptedData;
import org.bouncycastle.openpgp.PGPEncryptedDataGenerator;
import org.bouncycastle.openpgp.PGPException;
import org.bouncycastle.openpgp.PGPKeyPair;
import org.bouncycastle.openpgp.PGPKeyRingGenerator;
import org.bouncycastle.openpgp.PGPLiteralData;
import org.bouncycastle.openpgp.PGPLiteralDataGenerator;
import org.bouncycastle.openpgp.PGPObjectFactory;
import org.bouncycastle.openpgp.PGPPrivateKey;
import org.bouncycastle.openpgp.PGPPublicKey;
import org.bouncycastle.openpgp.PGPPublicKeyRing;
import org.bouncycastle.openpgp.PGPPublicKeyRingCollection;
import org.bouncycastle.openpgp.PGPSecretKey;
import org.bouncycastle.openpgp.PGPSecretKeyRing;
import org.bouncycastle.openpgp.PGPSignature;
import org.bouncycastle.openpgp.PGPSignatureGenerator;
import org.bouncycastle.openpgp.PGPSignatureList;
import org.bouncycastle.openpgp.PGPSignatureSubpacketGenerator;
import org.bouncycastle.openpgp.PGPUtil;
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
import org.bouncycastle.openpgp.operator.jcajce.JcaPGPContentVerifierBuilderProvider;
import org.bouncycastle.openpgp.operator.jcajce.JcaPGPDigestCalculatorProviderBuilder;
import org.bouncycastle.openpgp.operator.jcajce.JcePBESecretKeyDecryptorBuilder;
import org.bouncycastle.openpgp.operator.jcajce.JcePBESecretKeyEncryptorBuilder;
import org.bouncycastle.openpgp.operator.jcajce.JcePGPDataEncryptorBuilder;
import org.bouncycastle.openpgp.operator.jcajce.JcePublicKeyKeyEncryptionMethodGenerator;
import org.bouncycastle.util.encoders.Hex;
import org.witness.proofmode.ProofMode;

import java.io.BufferedInputStream;
import java.io.File;
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


    /*
     * verify the signature in in against the file fileName.
     */
    public static boolean verifySignature(
            InputStream          fileStream,
            InputStream     fileSignatureDetached,
            PGPPublicKey     publicKey)
            throws GeneralSecurityException, IOException, PGPException
    {
        InputStream inSig = PGPUtil.getDecoderStream(fileSignatureDetached);

        PGPObjectFactory pgpFact = new PGPObjectFactory(inSig, null);
        PGPSignatureList pgpSignatureList;

        Object    o = pgpFact.nextObject();
        if (o instanceof PGPCompressedData)
        {
            PGPCompressedData             c1 = (PGPCompressedData)o;

            pgpFact = new PGPObjectFactory(c1.getDataStream(), null);

            pgpSignatureList = (PGPSignatureList)pgpFact.nextObject();
        }
        else
        {
            pgpSignatureList = (PGPSignatureList)o;
        }

        PGPSignature proofSig = pgpSignatureList.get(0);
        proofSig.init(new JcaPGPContentVerifierBuilderProvider().setProvider(ProofMode.getProvider()), publicKey);

        int ch;
        while ((ch = fileStream.read()) != -1)
        {
            proofSig.update((byte)ch);
        }

        fileStream.close();

        return proofSig.verify();
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

        BouncyCastleProvider prov = ProofMode.getProvider();

        PBESecretKeyDecryptor keyDecryptor = new JcePBESecretKeyDecryptorBuilder().setProvider(prov).build(pass);
        PGPPrivateKey            pgpPrivKey = skey.extractPrivateKey(keyDecryptor);
        PGPSignatureGenerator sGen = new PGPSignatureGenerator(new JcaPGPContentSignerBuilder(skey.getPublicKey().getAlgorithm(), PGPUtil.SHA256).setProvider(prov));

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