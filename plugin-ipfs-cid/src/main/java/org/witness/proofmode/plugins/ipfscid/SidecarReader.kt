package org.witness.proofmode.plugins.ipfscid

import timber.log.Timber

object SidecarReader {
    fun decodeAndNormalize(
        jsonBytes: ByteArray,
        proofSetHash: String,
        mediaMimeType: String?,
    ): SidecarSnapshot {
        val snapshot = IpfsCidSidecar.decode(jsonBytes)
        val bareKey = proofSetHash
        val bareCid = snapshot.files[bareKey] ?: return snapshot
        val bareTsize = snapshot.tsizes[bareKey] ?: return snapshot

        val qualifiedName = MediaLinkNaming.manifestLinkNameForMedia(proofSetHash, mediaMimeType)
        if (qualifiedName == bareKey) return snapshot

        val migratedFiles = snapshot.files.toMutableMap()
        val migratedTsizes = snapshot.tsizes.toMutableMap()
        migratedFiles.remove(bareKey)
        migratedTsizes.remove(bareKey)
        if (!migratedFiles.containsKey(qualifiedName)) {
            migratedFiles[qualifiedName] = bareCid
            migratedTsizes[qualifiedName] = bareTsize
        } else {
            Timber.d(
                "SidecarReader: bare media key %s present alongside %s — dropping bare key",
                bareKey,
                qualifiedName,
            )
        }
        return SidecarSnapshot(
            rootCid = snapshot.rootCid,
            files = migratedFiles,
            tsizes = migratedTsizes,
        )
    }
}
