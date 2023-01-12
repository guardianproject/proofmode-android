import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.witness.proofmode.ProofMode.OPENPGP_FILE_TAG;
import static org.witness.proofmode.ProofMode.PROOF_FILE_TAG;

import android.content.Context;
import android.content.res.AssetManager;
import android.net.Uri;

import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.FixMethodOrder;
import org.junit.Test;
import org.junit.runners.MethodSorters;
import org.witness.proofmode.ProofMode;
import org.witness.proofmode.crypto.HashUtils;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import timber.log.Timber;

@FixMethodOrder(MethodSorters.NAME_ASCENDING)
public class ProofmodeGenerationTests {

    @Test
    public void proofModeGenerator_GenerateBytes_ReturnsTrue ()
    {
        Timber.plant(new Timber.DebugTree());

        Context context = InstrumentationRegistry.getInstrumentation().getContext();
        String testFile = "102474397-prooftestbytes";
        Uri uriMedia = Uri.parse("assets://" + testFile);

        boolean useDeviceIds = true;
        boolean useLocation = true;
        boolean useNetworks = true;
        boolean useNotarization = true;
        ProofMode.setProofPoints(context, useDeviceIds, useLocation, useNetworks, useNotarization);

        final ByteArrayOutputStream result = new ByteArrayOutputStream();
        final AssetManager assets = context.getAssets();
        try {
            InputStream inputStream = assets.open(testFile);
            try {

                byte[] buffer = new byte[4096];
                int length;

                while ((length = inputStream.read(buffer)) > 0) {
                    result.write(buffer, 0, length);
                }

            } catch (IOException e) {
                assertFalse(false);
            }
            inputStream.close();

            String mediaMime = "image/jpeg";
            String hash = ProofMode.generateProof(context, uriMedia, result.toByteArray(), mediaMime);
            Timber.i("hash generated: " + hash);
            assertNotNull(hash);

            File fileDirProof = ProofMode.getProofDir(context, hash);
            assertNotNull(fileDirProof);

            File fileMediaSig = new File(fileDirProof, hash + OPENPGP_FILE_TAG);
            assertTrue(fileMediaSig.exists());

            File fileMediaProof = new File(fileDirProof, hash + PROOF_FILE_TAG);
            assertTrue(fileMediaProof.exists());

            File fileMediaProofSig = new File(fileDirProof, hash + PROOF_FILE_TAG + OPENPGP_FILE_TAG);
            assertTrue(fileMediaProofSig.exists());

        } catch (Exception e) {
            e.printStackTrace();
            fail();

        }


    }

    @Test
    public void proofModeGenerator_VerifySignature_ReturnsTrue ()
    {
        Timber.plant(new Timber.DebugTree());

        Context context = InstrumentationRegistry.getInstrumentation().getContext();
        String testFile = "102474397-prooftestbytes";

        final AssetManager assets = context.getAssets();
        try {
            InputStream is = assets.open(testFile);
            String mediaHashSha256Check = HashUtils.getSHA256FromFileContent(is);
            assertNotNull(mediaHashSha256Check);
            is.close();

            File fileDirProof = ProofMode.getProofDir(context, mediaHashSha256Check);
            assertNotNull(fileDirProof);

            File[] files = fileDirProof.listFiles();
            assertNotNull(files);

            File fileZip = new File (fileDirProof.getParent(),fileDirProof.getName() + ".zip");
            zipProof(files, fileZip,ProofMode.getPublicKeyString(context));
            assertTrue(fileZip.exists());

            //verify specific file and hash
            is = assets.open(testFile);
            boolean verifiedIntegrity = ProofMode.verifyProofZip(context, mediaHashSha256Check, is, new FileInputStream(fileZip));
            assertTrue(verifiedIntegrity);
            is.close();

            //verify all contents
            is = assets.open(testFile);
            verifiedIntegrity = ProofMode.verifyProofZip(context, Uri.fromFile(fileZip));
            assertTrue(verifiedIntegrity);
            is.close();

            //verify all contents using FileDescriptor
            verifiedIntegrity = ProofMode.verifyProofZip(context, new FileInputStream(fileZip).getFD());
            assertTrue(verifiedIntegrity);

        } catch (Exception e) {
            e.printStackTrace();
            fail();

        }


    }

    public void zipProof(File[] files, File fileZip, String publicKeyString) throws IOException {

        BufferedInputStream origin;
        FileOutputStream dest = new FileOutputStream(fileZip);
        ZipOutputStream out = new ZipOutputStream(new BufferedOutputStream(
                dest));
        int BUFFER = 1024;
        byte[] data = new byte[BUFFER];

        for (File proofFile : files) {
            try {
                String fileName = proofFile.getName();
                Timber.d("adding to zip: " + fileName);
                origin = new BufferedInputStream(new FileInputStream(proofFile), BUFFER);
                ZipEntry entry = new ZipEntry(fileName);
                out.putNextEntry(entry);
                int count;

                while ((count = origin.read(data, 0, BUFFER)) != -1) {
                    out.write(data, 0, count);
                }
                origin.close();
            }
            catch (Exception e)
            {
                Timber.d(e, "Failed adding URI to zip: " + proofFile.getName());
            }
        }

        Timber.d("Adding public key");
        //add public key
        ZipEntry entry = new ZipEntry("pubkey.asc");
        out.putNextEntry(entry);
        out.write(publicKeyString.getBytes());



        Timber.d("Zip complete");

        out.close();

    }



}
