package org.witness.proofmode.plugins.lp.wallet.auth.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OtpDigitsTest {

    @Test
    fun sanitizeStripsNonDigits() = assertEquals("1234", OtpDigits.sanitize("12a3b4"))

    @Test
    fun sanitizeCapsAtSix() = assertEquals("123456", OtpDigits.sanitize("1234567890"))

    @Test
    fun sanitizeAcceptsPastedSpaces() = assertEquals("123456", OtpDigits.sanitize("123 456"))

    @Test
    fun sanitizeKeepsOnlyAsciiDigits() {
        assertEquals("1245", OtpDigits.sanitize("12٣45６"))
        assertEquals("", OtpDigits.sanitize("١٢٣٤٥٦"))
    }

    @Test
    fun activeIndexWhileFocused() {
        (0..5).forEach { assertEquals(it, OtpActiveBox.index(it, true)) }
    }

    @Test
    fun activeIndexNullWhenFull() = assertNull(OtpActiveBox.index(6, true))

    @Test
    fun activeIndexNullWhenNotFocused() {
        (0..6).forEach { assertNull(OtpActiveBox.index(it, false)) }
    }
}
