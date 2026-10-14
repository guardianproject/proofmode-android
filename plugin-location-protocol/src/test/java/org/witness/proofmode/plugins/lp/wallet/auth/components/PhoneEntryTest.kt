package org.witness.proofmode.plugins.lp.wallet.auth.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PhoneEntryTest {

    @Test
    fun maskedAtEachDigitCount() {
        val digits = "4155550123"
        mapOf(
            0 to "", 1 to "(4", 2 to "(41", 3 to "(415", 4 to "(415) 5", 6 to "(415) 555",
            7 to "(415) 555-0", 10 to "(415) 555-0123",
        ).forEach { (count, text) ->
            assertEquals("$count digits", text, PhoneEntry.masked(digits.take(count)))
        }
    }

    @Test
    fun nationalDigitsTyping() {
        assertEquals("415", PhoneEntry.nationalDigits("415"))
        assertEquals("415555", PhoneEntry.nationalDigits("(415) 555-"))
        assertEquals("", PhoneEntry.nationalDigits(""))
        assertEquals("4", PhoneEntry.nationalDigits("(4*"))
        assertEquals("", PhoneEntry.nationalDigits("#"))
        assertEquals("4", PhoneEntry.nationalDigits("٤١٥４１５4")) // non-ASCII digits dropped
    }

    @Test
    fun plusOnePaste_stripsCountryCode() {
        assertEquals("4155550123", PhoneEntry.nationalDigits("+1 (415) 555-0123"))
        assertEquals("415", PhoneEntry.nationalDigits("  +1 415  "))
    }

    @Test
    fun elevenDigitsLeadingOne_stripsTheOne() {
        assertEquals("4155550123", PhoneEntry.nationalDigits("14155550123"))
    }

    @Test
    fun tenDigitsStartingWithOne_areKept() {
        assertEquals("1415555012", PhoneEntry.nationalDigits("1415555012"))
    }

    @Test
    fun capsAtTen() {
        assertEquals("4155550123", PhoneEntry.nationalDigits("41555501239999"))
    }

    @Test
    fun otherCountryCode_isRejected() {
        assertNull(PhoneEntry.nationalDigits("+44 7700 900123"))
        assertNull(PhoneEntry.nationalDigits("+")) // "+" with no digits: not "+1"
    }

    @Test
    fun plusNotAtStart_isRejected() {
        assertNull(PhoneEntry.nationalDigits("(415+44 7700 900123"))
        assertNull(PhoneEntry.nationalDigits("415+"))
        assertNull(PhoneEntry.nationalDigits("++1 415"))
        assertNull(PhoneEntry.nationalDigits("+1 415 +44 20"))
    }

    @Test
    fun checkTable() {
        assertEquals(PhoneEntry.Check.Valid("+14155550123"), PhoneEntry.check("4155550123"))
        assertEquals(PhoneEntry.Check.Incomplete, PhoneEntry.check(""))
        assertEquals(PhoneEntry.Check.Incomplete, PhoneEntry.check("415555"))
        assertEquals(PhoneEntry.Check.Incomplete, PhoneEntry.check("41555501234"))
        assertEquals(PhoneEntry.Check.Incomplete, PhoneEntry.check("41555501a3"))
    }

    @Test
    fun maskedNumber_isValid() {
        assertEquals(
            PhoneEntry.Check.Valid("+14155550123"),
            PhoneEntry.check(PhoneEntry.nationalDigits("(415) 555-0123")!!),
        )
    }

    @Test
    fun e164Table() {
        assertEquals("+14155550123", PhoneEntry.e164("(415) 555-0123"))
        assertEquals("+14155550123", PhoneEntry.e164("+1 (415) 555-0123"))
        assertEquals("+14155550123", PhoneEntry.e164("14155550123"))
        assertNull(PhoneEntry.e164("415555")) // incomplete
        assertNull(PhoneEntry.e164("+44 7700 900123")) // other country code
        assertNull(PhoneEntry.e164("44155552671")) // 11 digits, no leading 1: not truncated, rejected
        assertNull(PhoneEntry.e164("123456789012345")) // 15 digits: rejected
    }

    @Test
    fun constants() {
        assertEquals("1", PhoneEntry.COUNTRY_CODE)
        assertEquals("(___) ___-____", PhoneEntry.MASK)
        assertEquals(10, PhoneEntry.NATIONAL_LENGTH)
    }
}
