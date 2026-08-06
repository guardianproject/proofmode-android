package org.witness.proofmode.share

import android.graphics.Bitmap
import android.media.ExifInterface
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.witness.proofmode.TestProofModeApplication
import java.io.ByteArrayOutputStream
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = TestProofModeApplication::class)
class SocialImageUtilExifTest {

    private fun jpegWithExif(): ByteArray {
        val bmp = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)
        val baos = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, 100, baos)
        val bytes = baos.toByteArray()
        val srcFile = File.createTempFile("src", ".jpg")
        srcFile.writeBytes(bytes)
        ExifInterface(srcFile.absolutePath).apply {
            setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_ROTATE_90.toString())
            setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, "2024:06:15 12:34:56")
            setAttribute(ExifInterface.TAG_GPS_LATITUDE, "37/1,46/1,2971/100")
            setAttribute(ExifInterface.TAG_GPS_LATITUDE_REF, "N")
            setAttribute(ExifInterface.TAG_GPS_LONGITUDE, "122/1,25/1,910/100")
            setAttribute(ExifInterface.TAG_GPS_LONGITUDE_REF, "W")
            setAttribute(ExifInterface.TAG_GPS_ALTITUDE, "10/1")
            setAttribute(ExifInterface.TAG_GPS_ALTITUDE_REF, "0")
            saveAttributes()
        }
        return srcFile.readBytes()
    }

    @Test
    fun copyExifForSocialShare_copiesGpsAndDateTimeOriginal_andNormalsOrientation() {
        val source = jpegWithExif()
        val destBmp = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)
        val dest = File.createTempFile("dest", ".jpg")
        dest.outputStream().use { destBmp.compress(Bitmap.CompressFormat.JPEG, 80, it) }

        SocialImageUtil().copyExifForSocialShare(source, dest)

        val out = ExifInterface(dest.absolutePath)
        assertEquals("2024:06:15 12:34:56", out.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL))
        assertEquals("N", out.getAttribute(ExifInterface.TAG_GPS_LATITUDE_REF))
        assertEquals("W", out.getAttribute(ExifInterface.TAG_GPS_LONGITUDE_REF))
        assertNotNull(out.getAttribute(ExifInterface.TAG_GPS_LATITUDE))
        assertNotNull(out.getAttribute(ExifInterface.TAG_GPS_LONGITUDE))
        assertEquals("10/1", out.getAttribute(ExifInterface.TAG_GPS_ALTITUDE))
        val orientation = out.getAttributeInt(
            ExifInterface.TAG_ORIENTATION,
            ExifInterface.ORIENTATION_NORMAL,
        )
        assertEquals(ExifInterface.ORIENTATION_NORMAL, orientation)
        assertTrue(dest.length() > 0)
    }
}
