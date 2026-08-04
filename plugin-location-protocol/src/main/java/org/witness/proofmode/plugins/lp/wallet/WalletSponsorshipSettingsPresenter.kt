package org.witness.proofmode.plugins.lp.wallet

import org.witness.proofmode.plugins.wallet.infra.config.UuidFormat

data class ProjectIdValidation(val isValid: Boolean, val errorMessage: String? = null)

class WalletSponsorshipSettingsPresenter {

    fun displayProjectId(override: String?, buildDefault: String): String =
        override?.takeIf { it.isNotBlank() }.orEmpty()

    fun validateProjectIdInput(raw: String): ProjectIdValidation {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return ProjectIdValidation(isValid = true)
        return if (UuidFormat.isValid(trimmed)) {
            ProjectIdValidation(isValid = true)
        } else {
            ProjectIdValidation(isValid = false, errorMessage = "invalid-uuid")
        }
    }

    /**
     * Mirrors SigningSettingsActivity.configureServerDefault semantics:
     * never persist the build default itself — only explicit user overrides.
     */
    fun resolvePersistedOverride(raw: String, buildDefault: String): String? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null
        if (trimmed == buildDefault) return null
        return trimmed
    }
}
