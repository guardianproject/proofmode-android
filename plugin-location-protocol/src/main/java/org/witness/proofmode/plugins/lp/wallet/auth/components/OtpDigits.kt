package org.witness.proofmode.plugins.lp.wallet.auth.components

object OtpDigits {
    const val LENGTH = 6

    fun sanitize(raw: String): String {
        return raw.filter { c -> c in '0'..'9' }.take(LENGTH)
    }
}

object OtpActiveBox {
    fun index(codeCount: Int, isFocused: Boolean): Int? {
        return if (isFocused && codeCount < OtpDigits.LENGTH) codeCount else null
    }
}
