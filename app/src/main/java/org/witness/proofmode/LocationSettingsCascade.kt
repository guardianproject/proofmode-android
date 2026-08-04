package org.witness.proofmode

import androidx.annotation.StringRes

/**
 * Pure cascade gate + summary selection for [LocationSettingsActivity]. Extracted for unit tests (D12).
 */
internal object LocationSettingsCascade {

    data class Inputs(
        val locationSharingEnabled: Boolean,
        val lpEnabled: Boolean,
        val walletConnected: Boolean,
        val walletDiagnosticsConnected: Boolean,
        val walletStackRegistered: Boolean,
        val activationState: LpActivationState,
        val lpSwitchChecked: Boolean,
        val cidSwitchChecked: Boolean,
        val walletAbbreviatedAddress: String?,
    )

    data class RowPresentation(
        val enabled: Boolean,
        @StringRes val summaryResId: Int,
        val summaryFormatArg: String? = null,
    )

    fun activationReady(state: LpActivationState): Boolean =
        state != LpActivationState.Activating && state != LpActivationState.Failed

    fun lpRowEnabled(inputs: Inputs): Boolean =
        inputs.locationSharingEnabled && inputs.activationState != LpActivationState.Activating

    fun autoCaptureEnabled(inputs: Inputs): Boolean =
        inputs.locationSharingEnabled &&
            inputs.lpEnabled &&
            inputs.walletConnected &&
            activationReady(inputs.activationState)

    fun cidEnabled(inputs: Inputs): Boolean =
        inputs.locationSharingEnabled &&
            inputs.lpEnabled &&
            activationReady(inputs.activationState)

    fun walletRowEnabled(inputs: Inputs): Boolean =
        (inputs.locationSharingEnabled && inputs.lpEnabled && inputs.walletStackRegistered) ||
            inputs.walletDiagnosticsConnected

    fun lpRowPresentation(inputs: Inputs): RowPresentation = RowPresentation(
        enabled = lpRowEnabled(inputs),
        summaryResId = when (inputs.activationState) {
            LpActivationState.Activating -> R.string.location_protocol_enable_activating
            LpActivationState.Failed -> R.string.location_protocol_enable_failed
            else -> if (!inputs.locationSharingEnabled) {
                R.string.location_protocol_enable_summary_location_required
            } else if (inputs.lpSwitchChecked) {
                R.string.location_protocol_enable_summary_on
            } else {
                R.string.location_protocol_enable_summary_off
            }
        },
    )

    fun autoCapturePresentation(inputs: Inputs): RowPresentation = RowPresentation(
        enabled = autoCaptureEnabled(inputs),
        summaryResId = when {
            inputs.activationState == LpActivationState.Activating &&
                inputs.locationSharingEnabled &&
                inputs.lpEnabled ->
                R.string.location_protocol_enable_activating
            !inputs.locationSharingEnabled ->
                R.string.location_protocol_auto_capture_summary_location_required
            !inputs.lpEnabled ->
                R.string.location_protocol_auto_capture_summary_lp_required
            !inputs.walletConnected ->
                R.string.location_protocol_auto_capture_summary_wallet_required
            else -> R.string.location_protocol_auto_capture_summary
        },
    )

    fun cidPresentation(inputs: Inputs): RowPresentation = RowPresentation(
        enabled = cidEnabled(inputs),
        summaryResId = when {
            inputs.activationState == LpActivationState.Activating &&
                inputs.locationSharingEnabled &&
                inputs.lpEnabled ->
                R.string.location_protocol_enable_activating
            !inputs.locationSharingEnabled ->
                R.string.compute_cids_enable_summary_location_required
            !inputs.lpEnabled ->
                R.string.compute_cids_enable_summary_lp_required
            inputs.cidSwitchChecked -> R.string.compute_cids_enable_summary_on
            else -> R.string.compute_cids_enable_summary_off
        },
    )

    fun walletPresentation(inputs: Inputs): RowPresentation {
        val enabled = walletRowEnabled(inputs)
        val summaryResId = when {
            inputs.walletDiagnosticsConnected ->
                R.string.location_wallet_summary_connected
            !activationReady(inputs.activationState) && !enabled ->
                R.string.location_protocol_enable_activating
            !inputs.locationSharingEnabled ->
                R.string.location_wallet_summary_location_required
            !inputs.lpEnabled ->
                R.string.location_wallet_summary_lp_required
            else -> R.string.location_wallet_row_summary
        }
        val formatArg = if (summaryResId == R.string.location_wallet_summary_connected) {
            inputs.walletAbbreviatedAddress ?: ""
        } else {
            null
        }
        return RowPresentation(enabled = enabled, summaryResId = summaryResId, summaryFormatArg = formatArg)
    }
}
