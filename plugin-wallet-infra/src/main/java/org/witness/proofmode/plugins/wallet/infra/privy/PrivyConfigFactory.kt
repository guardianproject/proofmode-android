package org.witness.proofmode.plugins.wallet.infra.privy

import io.privy.breadcrumbs.PrivyBreadcrumb
import io.privy.breadcrumbs.PrivyBreadcrumbHandler
import io.privy.logging.PrivyLogLevel
import io.privy.sdk.PrivyConfig
import org.witness.proofmode.plugins.wallet.infra.model.WalletSdkConfig
import timber.log.Timber

/**
 * Single place that builds the [PrivyConfig] used by the connector.
 *
 * [PrivyLogLevel] stays NONE; SDK diagnostics arrive as breadcrumbs and are forwarded to Timber.
 */
internal object PrivyConfigFactory {
    const val BREADCRUMB_TAG = "PrivyBreadcrumb"

    /** Data keys that may carry raw error text and are never logged. */
    private val DROPPED_KEYS = setOf("error", "error-message")

    /**
     * Receives breadcrumbs synchronously on SDK threads: formats and logs only, never throws,
     * no I/O and no locks.
     */
    val handler: PrivyBreadcrumbHandler = object : PrivyBreadcrumbHandler {
        override fun receive(breadcrumb: PrivyBreadcrumb) {
            try {
                Timber.tag(BREADCRUMB_TAG).i(
                    format(breadcrumb.event, breadcrumb.timestamp, breadcrumb.data)
                )
            } catch (_: Throwable) {
                // Diagnostics must never affect the SDK.
            }
        }
    }

    fun create(config: WalletSdkConfig): PrivyConfig = PrivyConfig(
        appId = config.privyAppId,
        appClientId = config.privyAppClientId,
        logLevel = PrivyLogLevel.NONE,
        breadcrumbHandler = handler,
    )

    /** Pure formatter; drops [DROPPED_KEYS] so raw error text never reaches logs. */
    fun format(event: String, timestamp: Long, data: Map<String, String>): String {
        val fields = data.entries
            .filter { it.key !in DROPPED_KEYS }
            .joinToString(separator = " ") { "${it.key}=${it.value}" }
        return buildString {
            append(event)
            append(" ts=").append(timestamp)
            if (fields.isNotEmpty()) append(' ').append(fields)
        }
    }
}
