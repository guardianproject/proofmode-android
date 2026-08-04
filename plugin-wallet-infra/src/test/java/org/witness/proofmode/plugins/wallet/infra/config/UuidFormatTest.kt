package org.witness.proofmode.plugins.wallet.infra.config

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UuidFormatTest {

    @Test
    fun isValidUuidFormat_acceptsCanonicalLowercase() {
        assertTrue(
            UuidFormat.isValid("550e8400-e29b-41d4-a716-446655440000"),
        )
    }

    @Test
    fun isValidUuidFormat_acceptsUppercase() {
        assertTrue(
            UuidFormat.isValid("550E8400-E29B-41D4-A716-446655440000"),
        )
    }

    @Test
    fun isValidUuidFormat_rejectsBlankAndGarbage() {
        assertFalse(UuidFormat.isValid(""))
        assertFalse(UuidFormat.isValid("not-a-uuid"))
        assertFalse(UuidFormat.isValid("550e8400-e29b-41d4-a716"))
    }
}
