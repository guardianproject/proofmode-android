package org.witness.proofmode.plugins.lp.wallet.auth.components

import org.junit.Assert.assertEquals
import org.junit.Test

class PrivyOtpViewTest {

    @Test
    fun `phoneNumberToE164 normalizes 10 digit us number`() {
        assertEquals("+15551234567", PrivyOtpView.phoneNumberToE164("(555) 123-4567"))
    }

    @Test
    fun `phoneNumberToE164 preserves country code for 11 digits`() {
        assertEquals("+14155552671", PrivyOtpView.phoneNumberToE164("1 (415) 555-2671"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `phoneNumberToE164 rejects short numbers`() {
        PrivyOtpView.phoneNumberToE164("555123")
    }

    @Test(expected = IllegalArgumentException::class)
    fun `phoneNumberToE164 rejects numbers longer than 15 digits`() {
        PrivyOtpView.phoneNumberToE164("1234567890123456")
    }
}
