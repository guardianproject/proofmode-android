package org.witness.proofmode.plugins.ipfscid

object MediaLinkNaming {
    data class CachedMediaLeaf(val name: String, val leafCid: String, val tsize: Long)

    fun extensionFromMimeType(mimeType: String?): String = when (mimeType) {
        "image/jpeg" -> "jpg"
        "image/png" -> "png"
        "video/mp4" -> "mp4"
        else -> "bin"
    }

    fun manifestLinkNameForMedia(proofSetHash: String, mimeType: String?): String =
        "$proofSetHash.${extensionFromMimeType(mimeType)}"

    /** Locates injected media leaf in sidecar — extension-qualified key only (bare hash migrated in SidecarReader). */
    fun findCachedMediaLeaf(sidecar: SidecarSnapshot, proofSetHash: String): CachedMediaLeaf? {
        for ((name, cid) in sidecar.files) {
            if (!name.startsWith("$proofSetHash.")) continue
            if (ProofSetCidMembershipPolicy.isExcludedFromManifest(name)) continue
            if (ProofSetCidMembershipPolicy.isCoreArtifactForTest(proofSetHash, name)) continue
            if (name == "$proofSetHash.ots" || name == "$proofSetHash.nostr") continue
            val t = sidecar.tsizes[name] ?: continue
            return CachedMediaLeaf(name, cid, t)
        }
        return null
    }
}
