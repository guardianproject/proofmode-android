import android.content.Context;
import android.net.Uri;

import org.witness.proofmode.ProofMode;
import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

 class ProofmodeGenerationTest {

    @Test
    public void proofModeGenerator_GenerateBytes_ReturnsTrue (Context context)
    {
        Uri uriMedia = Uri.parse("/test/file/1234");
        byte[] mediaBytes = null;
        String mediaMime = "image/jpeg";
        String hash = ProofMode.generateProof(context, uriMedia, mediaBytes, mediaMime);
        assertTrue(hash != null);
    }
}
