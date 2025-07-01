package org.witness.proofmode.org.witness.proofmode.share

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.media.ExifInterface
import android.net.Uri
import android.os.Build
import com.google.android.gms.common.util.IOUtils
import com.google.zxing.BarcodeFormat
import com.google.zxing.MultiFormatWriter
import com.google.zxing.common.BitMatrix
import org.witness.proofmode.R
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.lang.reflect.InvocationTargetException


class SocialImageUtil {

    fun createImageCard (context: Context, sourceImage : Uri, verifyUrl: String?) : Uri {

        var ba = IOUtils.readInputStreamFully(context.contentResolver.openInputStream(sourceImage)!!)
        var bm = decodeBitmapWithRotation(ba)
        val waterMark = BitmapFactory.decodeResource(context.getResources(), R.drawable.proofmode128)
        var qrcode : Bitmap? = null

        verifyUrl?.let {
            qrcode = encodeAsBitmap(it, waterMark.width, waterMark.height)
        }

        bm = addWaterMark(context, bm, waterMark, qrcode,null)
        var file = File.createTempFile("share",".jpg");
        bm.compress(Bitmap.CompressFormat.JPEG,80,FileOutputStream(file))
        return Uri.fromFile(file)

    }

    fun addWaterMark(context: Context, src: Bitmap, waterMark: Bitmap, qrcode: Bitmap?, waterText: String?): Bitmap {
        val w = src.width
        val h = src.height
        val result = Bitmap.createBitmap(
            w, h,
            src.config!!
        )
        val canvas: Canvas = Canvas(result)
        canvas.drawBitmap(src, 0f, 0f, null)

        canvas.drawBitmap(waterMark, 5f, src.height - waterMark.height - 0f, null)

        qrcode?.let {
            canvas.drawBitmap(it, src.width- qrcode.width - 0f, src.height - waterMark.height - 0f, null)

        }

        waterText?.let {
            val paint: Paint = Paint()
            paint.color = context.resources.getColor(android.R.color.white)
            paint.textSize = 12f
            paint.isAntiAlias = true
            canvas.drawText(it, 5f, 10f, paint)
        }

        return result
    }

    fun decodeBitmapWithRotation(picture: ByteArray): Bitmap {
        val bitmap = BitmapFactory.decodeByteArray(picture, 0, picture.size)
        val matrix: Matrix = getBitmapRotation(picture)
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    fun getBitmapRotation(picture: ByteArray): Matrix {
        var orientation = 0
        try {
            orientation = getExifOrientation(ByteArrayInputStream(picture))
        } catch (e: IOException) {
            e.printStackTrace()
        }
        val matrix: Matrix = Matrix()
        when (orientation) {
            2 -> matrix.setScale(-1f, 1f)
            3 -> matrix.setRotate(180f)
            4 -> {
                matrix.setRotate(180f)
                matrix.postScale(-1f, 1f)
            }

            5 -> {
                matrix.setRotate(90f)
                matrix.postScale(-1f, 1f)
            }

            6 -> matrix.setRotate(90f)
            7 -> {
                matrix.setRotate(-90f)
                matrix.postScale(-1f, 1f)
            }

            8 -> matrix.setRotate(-90f)
        }
        return matrix
    }

    @Throws(IOException::class)
    private fun getExifOrientation(inputStream: InputStream): Int {
        var orientation = 1

        var exif = ExifInterface(inputStream)
        orientation = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, 1);

        return orientation
    }

    fun encodeAsBitmap(source: String, width: Int, height: Int):Bitmap? {

        val result: BitMatrix = try {
            MultiFormatWriter().encode(source, BarcodeFormat.QR_CODE, width, height, null)
        } catch (e: Exception) {
            return null
        }

        val w = result.width
        val h = result.height
        val pixels = IntArray(w * h)

        for (y in 0 until h) {
            val offset = y * w
            for (x in 0 until w) {
                pixels[offset + x] = if (result[x, y]) Color.BLACK else Color.WHITE
            }
        }

        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        bitmap.setPixels(pixels, 0, width, 0, 0, w, h)

        return bitmap
    }
}