package org.witness.proofmode.importing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.witness.proofmode.importing.ImportMediaValidator.Reason

class ImportMediaValidatorTest {

    // --- provenance ---

    @Test
    fun syntheticSourceTypes_foundInC2paJsonAndXmp() {
        val c2pa = """{"actions":[{"action":"c2pa.created","digitalSourceType":"http://cv.iptc.org/newscodes/digitalsourcetype/trainedAlgorithmicMedia"}]}"""
        assertEquals(setOf("trainedalgorithmicmedia"), ImportMediaValidator.findSyntheticSourceTypes(c2pa))

        val bare = """{"digitalSourceType": "compositeSynthetic"}"""
        assertEquals(setOf("compositesynthetic"), ImportMediaValidator.findSyntheticSourceTypes(bare))

        val xmp = """<rdf:Description Iptc4xmpExt:DigitalSourceType="http://cv.iptc.org/newscodes/digitalsourcetype/compositeWithTrainedAlgorithmicMedia"/>"""
        assertEquals(setOf("compositewithtrainedalgorithmicmedia"), ImportMediaValidator.findSyntheticSourceTypes(xmp))
    }

    @Test
    fun cameraSourceTypes_areNotSynthetic() {
        val c2pa = """{"digitalSourceType":"http://cv.iptc.org/newscodes/digitalsourcetype/digitalCapture"}"""
        assertTrue(ImportMediaValidator.findSyntheticSourceTypes(c2pa).isEmpty())
        val computational = """{"digitalSourceType":"http://cv.iptc.org/newscodes/digitalsourcetype/computationalCapture"}"""
        assertTrue(ImportMediaValidator.findSyntheticSourceTypes(computational).isEmpty())
    }

    // --- EXIF ---

    private val cameraExif = mapOf(
        "Make" to "samsung",
        "Model" to "SM-A366B",
        "DateTimeOriginal" to "2026:11:01 10:00:00",
        "ExposureTime" to "0.01",
        "Software" to "A366BXXU1AYA1",
    )

    @Test
    fun photoExif_cameraOriginal_passes() {
        assertTrue(ImportMediaValidator.photoExifReasons(cameraExif::get).isEmpty())
    }

    @Test
    fun photoExif_missingCameraFields_fails() {
        for (drop in listOf("Make", "Model", "DateTimeOriginal", "ExposureTime")) {
            val exif = cameraExif - drop
            assertEquals(drop, listOf(Reason.MISSING_CAMERA_EXIF), ImportMediaValidator.photoExifReasons(exif::get))
        }
        // DateTime is an acceptable stand-in for DateTimeOriginal.
        val withDateTime = cameraExif - "DateTimeOriginal" + ("DateTime" to "2026:11:01 10:00:00")
        assertTrue(ImportMediaValidator.photoExifReasons(withDateTime::get).isEmpty())
    }

    @Test
    fun photoExif_editingSoftware_fails() {
        val edited = cameraExif + ("Software" to "Adobe Photoshop 25.0 (Windows)")
        assertEquals(listOf(Reason.EDITING_SOFTWARE), ImportMediaValidator.photoExifReasons(edited::get))
        assertFalse(ImportMediaValidator.isEditingSoftware(null))
        assertTrue(ImportMediaValidator.isEditingSoftware("Midjourney v6"))
    }

    // --- aspect ratio ---

    @Test
    fun aspectRatio_cameraFormatsPass_eitherOrientation() {
        assertTrue(ImportMediaValidator.matchesCameraAspectRatio(4000, 3000, isVideo = false))
        assertTrue(ImportMediaValidator.matchesCameraAspectRatio(3000, 4000, isVideo = false))
        assertTrue(ImportMediaValidator.matchesCameraAspectRatio(6000, 4000, isVideo = false))
        assertTrue(ImportMediaValidator.matchesCameraAspectRatio(1920, 1080, isVideo = true))
        assertTrue(ImportMediaValidator.matchesCameraAspectRatio(1080, 1920, isVideo = true))
    }

    @Test
    fun aspectRatio_oddShapesFail() {
        assertFalse(ImportMediaValidator.matchesCameraAspectRatio(1000, 300, isVideo = false))
        assertFalse(ImportMediaValidator.matchesCameraAspectRatio(1300, 1000, isVideo = false))
        // 3:2 is a stills format, not a phone video format.
        assertFalse(ImportMediaValidator.matchesCameraAspectRatio(1500, 1000, isVideo = true))
        assertFalse(ImportMediaValidator.matchesCameraAspectRatio(0, 1000, isVideo = false))
    }
}
