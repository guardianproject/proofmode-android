package org.witness.proofmode.plugins.lp.wallet.auth.components

/**
 * Phone-number rules for the wallet SMS sign-in step (US/Canada only, +1).
 *
 * [nationalDigits] is the typing rule (extra digits are truncated); [e164] is the submit rule
 * (anything but exactly ten national digits is rejected).
 */
object PhoneEntry {
    const val COUNTRY_CODE = "1"
    const val MASK = "(___) ___-____"
    const val NATIONAL_LENGTH = 10

    sealed class Check {
        data class Valid(val e164: String) : Check()
        data object Incomplete : Check()
    }

    /** National digits for the field as typed or pasted, capped at [NATIONAL_LENGTH]; null when the input is not a +1 number. */
    fun nationalDigits(raw: String): String? = strippedDigits(raw)?.take(NATIONAL_LENGTH)

    /** [digits] laid out in [MASK], stopping where the digits run out. */
    fun masked(digits: String): String {
        val out = StringBuilder()
        var next = 0
        for (maskChar in MASK) {
            if (next >= digits.length) break
            if (maskChar == '_') {
                out.append(digits[next])
                next++
            } else {
                out.append(maskChar)
            }
        }
        return out.toString()
    }

    fun check(national: String): Check =
        if (national.length == NATIONAL_LENGTH && national.all { it in '0'..'9' }) {
            Check.Valid("+$COUNTRY_CODE$national")
        } else {
            Check.Incomplete
        }

    /** E.164 form of [raw], or null unless exactly [NATIONAL_LENGTH] national digits remain. */
    fun e164(raw: String): String? = strippedDigits(raw)?.let { (check(it) as? Check.Valid)?.e164 }

    private fun strippedDigits(raw: String): String? {
        val trimmed = raw.trim()
        if (trimmed.lastIndexOf('+') > 0) return null
        val digits = trimmed.filter { it in '0'..'9' }
        return when {
            trimmed.startsWith("+") ->
                if (digits.startsWith(COUNTRY_CODE)) digits.drop(COUNTRY_CODE.length) else null
            digits.length == NATIONAL_LENGTH + 1 && digits.startsWith(COUNTRY_CODE) ->
                digits.drop(COUNTRY_CODE.length)
            else -> digits
        }
    }
}
