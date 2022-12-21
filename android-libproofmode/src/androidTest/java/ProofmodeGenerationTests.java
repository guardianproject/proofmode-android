import android.content.Context;
import android.content.res.AssetManager;
import android.net.Uri;

import org.witness.proofmode.ProofMode;
import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.witness.proofmode.ProofMode.PROOF_FILE_TAG;

import androidx.test.platform.app.InstrumentationRegistry;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;

import timber.log.Timber;

public class ProofmodeGenerationTests {

    @Test
    public void proofModeGenerator_GenerateBytes_ReturnsTrue ()
    {
        Timber.plant(new Timber.DebugTree());

        Context context = InstrumentationRegistry.getInstrumentation().getContext();
        Uri uriMedia = Uri.parse("assets://102474397-prooftestbytes");
        final ByteArrayOutputStream result = new ByteArrayOutputStream();
        final AssetManager assets = context.getAssets();
        try {
            InputStream inputStream = assets.open("102474397-prooftestbytes");
            try {

                byte[] buffer = new byte[4096];
                int length;

                while ((length = inputStream.read(buffer)) > 0) {
                    result.write(buffer, 0, length);
                }

            } catch (IOException e) {
                assertFalse(false);
            }

            String mediaMime = "image/jpeg";
            String hash = ProofMode.generateProof(context, uriMedia, result.toByteArray(), mediaMime);

            Timber.i("hash generated: " + hash);

            assertTrue(hash != null);

            File fileDirProof = ProofMode.getProofDir(context, hash);
            assertTrue(fileDirProof != null);

            File fileMediaProof = new File(fileDirProof, hash + PROOF_FILE_TAG);
            assertTrue(fileMediaProof.exists());

        } catch (IOException e) {
            e.printStackTrace();
            assertFalse(false);

        }


    }
}
