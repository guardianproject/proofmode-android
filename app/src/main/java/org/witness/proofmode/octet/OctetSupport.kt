package org.witness.proofmode.org.witness.proofmode.octet

import org.witness.proofmode.BuildConfig

/** Values come from secrets.properties via BuildConfig (see app/build.gradle.kts). */
object OctetSupport {

    /**
     * Octet can start with a license key, or in debug builds with just a sandbox bypass token
     * (the license key is then empty).
     */
    val isConfigured: Boolean
        get() = BuildConfig.OCTET_LICENSE_KEY.isNotEmpty() ||
            (BuildConfig.DEBUG && BuildConfig.OCTET_SANDBOX_BYPASS_TOKEN.isNotEmpty())
}
