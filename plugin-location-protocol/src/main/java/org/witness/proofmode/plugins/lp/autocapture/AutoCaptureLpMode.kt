package org.witness.proofmode.plugins.lp.autocapture

enum class AutoCaptureLpMode {
    OFF,
    OFFCHAIN,
    ONCHAIN,
    BOTH;

    val isActive: Boolean
        get() = this != OFF

    companion object {
        const val PREF_OFF = "off"
        const val PREF_OFFCHAIN = "offchain"
        const val PREF_ONCHAIN = "onchain"
        const val PREF_BOTH = "both"

        fun fromPreference(value: String?): AutoCaptureLpMode =
            when (value?.lowercase()) {
                PREF_OFFCHAIN -> OFFCHAIN
                PREF_ONCHAIN -> ONCHAIN
                PREF_BOTH -> BOTH
                else -> OFF
            }

        fun toPreference(mode: AutoCaptureLpMode): String =
            when (mode) {
                OFF -> PREF_OFF
                OFFCHAIN -> PREF_OFFCHAIN
                ONCHAIN -> PREF_ONCHAIN
                BOTH -> PREF_BOTH
            }
    }
}
