package org.witness.proofmode.crypto;

import java.io.BufferedInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

import timber.log.Timber;

/**
 * Created by n8fr8 on 10/9/16.
 */
public class HashUtils {

    public static String getSHA256FromFileContent(InputStream is)
    {

        try
        {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[1024*16]; //16k chunks
            BufferedInputStream fis = new BufferedInputStream(is);
            int n = 0;
            while (n != -1)
            {
                n = fis.read(buffer);
                if (n > 0)
                {
                    digest.update(buffer, 0, n);
                }
            }
            byte[] digestResult = digest.digest();
            return asHex(digestResult);
        }
        catch (FileNotFoundException e)
        {
            Timber.w(e,"Could not find the file to generate hash");
            return null;
        }
        catch (IOException e)
        {
            Timber.w(e,"Error generating hash; IOError");
            return null;
        }
        catch (NoSuchAlgorithmException e)
        {
            Timber.w(e,"Error generating hash; No such algorithm");
            return null;
        }
    }

    public static String asHex(byte[] arrayBytes) {
        StringBuffer stringBuffer = new StringBuffer();
        for (int i = 0; i < arrayBytes.length; i++) {
            stringBuffer.append(Integer.toString((arrayBytes[i] & 0xff) + 0x100, 16)
                    .substring(1));
        }
        return stringBuffer.toString();
    }
}
