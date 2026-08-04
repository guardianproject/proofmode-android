package org.witness.proofmode.plugins.wallet.infra.config

object UuidFormat {
    private val UUID_REGEX = Regex(
        "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$",
    )

    fun isValid(value: String?): Boolean =
        !value.isNullOrBlank() && UUID_REGEX.matches(value.trim())
}
