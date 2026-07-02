package org.witness.proofmode.plugins.ipfscid

import org.witness.proofmode.cid.NamedBytes
import org.witness.proofmode.cid.NamedLeafCid

object ProofSetCidManifest {
    fun composeByteBackedManifest(
        proofSetHash: String,
        manifestMemberBasenames: List<String>,
        diskBytesByBasename: Map<String, ByteArray>,
        mediaBytes: ByteArray,
        mediaMimeType: String?,
        includeOts: Boolean,
        includeNostr: Boolean,
    ): List<NamedBytes> {
        val mediaLink = MediaLinkNaming.manifestLinkNameForMedia(proofSetHash, mediaMimeType)
        val entries = mutableListOf(NamedBytes(mediaLink, mediaBytes))
        for (basename in manifestMemberBasenames) {
            val bytes = diskBytesByBasename[basename] ?: continue
            entries.add(NamedBytes(basename, bytes))
        }
        return entries
    }

    fun composeLeafBackedManifest(
        proofSetHash: String,
        manifestMemberBasenames: List<String>,
        sidecar: SidecarSnapshot,
        newLeafBytesByBasename: Map<String, ByteArray>,
        computeLeafFromBytes: (ByteArray) -> Pair<String, Long>,
    ): List<NamedLeafCid> {
        val leaves = mutableListOf<NamedLeafCid>()
        for (name in manifestMemberBasenames) {
            val cachedCid = sidecar.files[name]
            val cachedTsize = sidecar.tsizes[name]
            if (cachedCid != null && cachedTsize != null) {
                leaves.add(NamedLeafCid(name, cachedCid, cachedTsize))
                continue
            }
            val bytes = newLeafBytesByBasename[name] ?: continue
            val (leafCid, tsize) = computeLeafFromBytes(bytes)
            leaves.add(NamedLeafCid(name, leafCid, tsize))
        }
        MediaLinkNaming.findCachedMediaLeaf(sidecar, proofSetHash)?.let { media ->
            if (leaves.none { it.name == media.name }) {
                leaves.add(NamedLeafCid(media.name, media.leafCid, media.tsize))
            }
        }
        return leaves
    }
}
