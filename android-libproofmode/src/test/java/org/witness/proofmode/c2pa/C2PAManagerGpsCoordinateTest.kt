package org.witness.proofmode.c2pa

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression coverage for github issue #122: a photo taken west of the prime
 * meridian (a negative longitude) ended up with its EXIF/C2PA longitude
 * reported in the eastern hemisphere. The exif:GPSLatitude / exif:GPSLongitude
 * fields in the C2PA metadata assertion are XMP GPSCoordinate strings, not raw
 * signed doubles, so [C2PAManager.toXmpGpsCoordinate] has to strip the sign and
 * fold the hemisphere into the string itself.
 */
class C2PAManagerGpsCoordinateTest {

    @Test
    fun negativeLongitudeIsReportedAsWest() {
        // 6 degrees, 8 minutes, 54.154 seconds west, from the issue report.
        val decimalDegrees = -(6.0 + 8.0 / 60.0 + 54.154 / 3600.0)

        val result = C2PAManager.toXmpGpsCoordinate(decimalDegrees, "E", "W")

        assertTrue("expected a West reference, got: $result", result.endsWith("W"))
        assertTrue("expected no leading minus sign, got: $result", !result.startsWith("-"))
        assertEquals("6,8.902567W", result)
    }

    @Test
    fun positiveLongitudeIsReportedAsEast() {
        val result = C2PAManager.toXmpGpsCoordinate(6.148376, "E", "W")

        assertTrue("expected an East reference, got: $result", result.endsWith("E"))
        assertEquals("6,8.902560E", result)
    }

    @Test
    fun negativeLatitudeIsReportedAsSouth() {
        val result = C2PAManager.toXmpGpsCoordinate(-33.865143, "N", "S")

        assertTrue("expected a South reference, got: $result", result.endsWith("S"))
        assertTrue("expected no leading minus sign, got: $result", !result.startsWith("-"))
    }

    @Test
    fun positiveLatitudeIsReportedAsNorth() {
        val result = C2PAManager.toXmpGpsCoordinate(33.865143, "N", "S")

        assertTrue("expected a North reference, got: $result", result.endsWith("N"))
    }

    @Test
    fun zeroDegreesDefaultsToThePositiveRef() {
        // The prime meridian / equator aren't negative zero, so this should
        // resolve to the positive hemisphere letter rather than throw or flip.
        val result = C2PAManager.toXmpGpsCoordinate(0.0, "E", "W")

        assertTrue(result.endsWith("E"))
    }
}
