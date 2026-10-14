package org.witness.proofmode.plugins.lp.wallet.auth.components

import org.junit.Assert.assertEquals
import org.junit.Test

class PrivyOtpViewTest {

    @Test fun `tenDigits_prefixesPlusOne`() = assertEquals("+15551234567", PrivyOtpView.phoneNumberToE164("(555) 123-4567"))
    @Test fun `plusOnePrefix_isAccepted`() = assertEquals("+14155552671", PrivyOtpView.phoneNumberToE164("+1 (415) 555-2671"))
    @Test fun `elevenDigitsLeadingOne_isAccepted`() = assertEquals("+14155552671", PrivyOtpView.phoneNumberToE164("1 (415) 555-2671"))
    @Test(expected = IllegalArgumentException::class) fun `sixDigits_throws`() { PrivyOtpView.phoneNumberToE164("555123") }
    @Test(expected = IllegalArgumentException::class) fun `otherCountryCode_throws`() { PrivyOtpView.phoneNumberToE164("+44 7700 900123") }
    @Test(expected = IllegalArgumentException::class) fun `elevenDigitsNotLeadingOne_throws`() { PrivyOtpView.phoneNumberToE164("44155552671") }
    @Test(expected = IllegalArgumentException::class) fun `fifteenDigits_throws`() { PrivyOtpView.phoneNumberToE164("123456789012345") }
}
