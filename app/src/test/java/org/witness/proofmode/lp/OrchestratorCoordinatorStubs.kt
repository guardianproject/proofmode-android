package org.witness.proofmode.lp

import org.mockito.kotlin.any
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.mock
import org.witness.proofmode.plugins.lp.attestation.LocationProtocolAttestationCoordinator
import org.witness.proofmode.plugins.lp.attestation.LocationProtocolAttestationResult

internal fun mockOffchainRecordingCoordinator(
    invocations: MutableList<String>,
): LocationProtocolAttestationCoordinator =
    mock {
        onBlocking {
            attestOffchain(any(), any(), any(), any())
        } doAnswer { invocation ->
            invocations.add(invocation.getArgument(0))
            Result.success(mock<LocationProtocolAttestationResult>())
        }
    }

internal fun mockBothLegsRecordingCoordinator(
    legOrder: MutableList<String>,
): LocationProtocolAttestationCoordinator =
    mock {
        onBlocking {
            attestOffchain(any(), any(), any(), any())
        } doAnswer {
            legOrder.add("offchain")
            Result.success(mock<LocationProtocolAttestationResult>())
        }
        onBlocking {
            attestOnchain(any(), any(), any(), any(), any())
        } doAnswer {
            legOrder.add("onchain")
            Result.success(mock<LocationProtocolAttestationResult>())
        }
    }
