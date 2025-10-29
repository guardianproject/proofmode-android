package org.witness.proofmode.c2pa

enum class SigningMode(val displayName: String, val description: String, val requiresConfiguration: Boolean = false) {

    DEFAULT(
        displayName = "Default",
        description = "Use the included test certificate for signing",
        requiresConfiguration = false,
    ),
    KEYSTORE(
        displayName = "Android Keystore",
        description = "Generate and store keys in Android Keystore",
        requiresConfiguration = true,
    ),
    HARDWARE(
        displayName = "Hardware Security",
        description = "Use hardware-backed keys with StrongBox or TEE",
        requiresConfiguration = true,
    ),
    CUSTOM(
        displayName = "Custom",
        description = "Upload your own certificate and private key",
        requiresConfiguration = true,
    ),
    REMOTE(
        displayName = "Remote",
        description = "Use a remote signing service",
        requiresConfiguration = true,
    ),
    ;

    companion object {
        fun fromString(value: String): SigningMode = entries.find { it.name == value } ?: DEFAULT
    }
}
