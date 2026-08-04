package org.witness.proofmode.plugins.ipfscid

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.witness.proofmode.cid.CidOptions

data class SidecarSnapshot(
    val rootCid: String,
    val files: Map<String, String>,
    val tsizes: Map<String, Long>,
)

object IpfsCidSidecar {
    const val SIDECAR_SUFFIX = ".ipfs-cids.json"

    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }

    fun sidecarBasename(proofSetHash: String): String = "$proofSetHash$SIDECAR_SUFFIX"

    fun isLateSnapshotBasename(artifactBasename: String): Boolean =
        artifactBasename.contains(".ipfs-cids.late-") && artifactBasename.endsWith(".json")

    @Serializable
    private data class ArtifactDocument(
        val version: Int = 1,
        val rootCid: String,
        val files: Map<String, String>,
        val tsizes: Map<String, Long> = emptyMap(),
        val options: OptionsDocument,
        val computedAtMs: Long,
    )

    @Serializable
    private data class OptionsDocument(
        val chunkSize: Int,
        val cidVersion: Int,
        val rawLeaves: Boolean,
        val wrapWithDirectory: Boolean,
        val shardThreshold: Long,
        val blockSizeLimit: Long? = null,
        val hasher: String = "sha2-256",
    )

    fun encode(
        rootCid: String,
        files: Map<String, String>,
        computedAtMs: Long,
        tsizes: Map<String, Long> = emptyMap(),
        options: CidOptions = CidOptions.DEFAULT,
    ): ByteArray {
        val doc = ArtifactDocument(
            rootCid = rootCid,
            files = files,
            tsizes = tsizes,
            computedAtMs = computedAtMs,
            options = OptionsDocument(
                chunkSize = options.chunkSize,
                cidVersion = options.cidVersion,
                rawLeaves = options.rawLeaves,
                wrapWithDirectory = options.wrapWithDirectory,
                shardThreshold = options.shardThreshold,
                blockSizeLimit = options.blockSizeLimit,
            ),
        )
        return json.encodeToString(doc).toByteArray(Charsets.UTF_8)
    }

    fun decode(bytes: ByteArray): SidecarSnapshot {
        val doc = json.decodeFromString<ArtifactDocument>(bytes.toString(Charsets.UTF_8))
        return SidecarSnapshot(
            rootCid = doc.rootCid,
            files = doc.files,
            tsizes = doc.tsizes,
        )
    }
}
