package org.witness.proofmode.octet

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.witness.proofmode.org.witness.proofmode.octet.OctetNotarizationProvider.Companion.parseLocationClaim
import org.witness.proofmode.org.witness.proofmode.octet.OctetNotarizationProvider.LocationClaim

class OctetLocationClaimTest {

    private fun proof(lat: String, lon: String): (String) -> String? =
        mapOf("Location.Latitude" to lat, "Location.Longitude" to lon)::get

    @Test
    fun parse_validLocation() {
        assertEquals(LocationClaim(40.5, -74.25), parseLocationClaim(proof("40.5", "-74.25")))
    }

    @Test
    fun parse_missingEmptyOrOutOfRange_isNull() {
        assertNull(parseLocationClaim { null })
        assertNull(parseLocationClaim(proof("", "")))
        assertNull(parseLocationClaim(proof("91.0", "10.0")))
        assertNull(parseLocationClaim(proof("10.0", "181.0")))
    }
}
