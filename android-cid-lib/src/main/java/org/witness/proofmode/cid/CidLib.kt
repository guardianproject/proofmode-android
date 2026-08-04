package org.witness.proofmode.cid

import org.witness.proofmode.cid.uniffi.NamedEntry
import org.witness.proofmode.cid.uniffi.PrecomputedLeaf
import org.witness.proofmode.cid.uniffi.computeFileCid as uniffiComputeFileCid
import org.witness.proofmode.cid.uniffi.computeFileLeafCidAndTsize as uniffiComputeFileLeafCidAndTsize
import org.witness.proofmode.cid.uniffi.computeProofSetCid as uniffiComputeProofSetCid
import org.witness.proofmode.cid.uniffi.computeProofSetCidFromLeaves as uniffiComputeProofSetCidFromLeaves

object CidLib {
    @Volatile
    private var loaded = false

    @Synchronized
    private fun ensureLoaded() {
        if (loaded) return
        System.loadLibrary("rust_cid_lib")
        loaded = true
    }

    fun computeFileCid(bytes: ByteArray, options: CidOptions = CidOptions.DEFAULT): String {
        ensureLoaded()
        return uniffiComputeFileCid(
            fileBytes = bytes,
            chunkSize = options.chunkSize.toUInt(),
            cidVersion = options.cidVersion.toUInt(),
            rawLeaves = options.rawLeaves,
        )
    }

    fun computeFileLeafCidAndTsize(
        bytes: ByteArray,
        options: CidOptions = CidOptions.DEFAULT,
    ): FileLeafCidResult {
        ensureLoaded()
        val output = uniffiComputeFileLeafCidAndTsize(
            fileBytes = bytes,
            chunkSize = options.chunkSize.toUInt(),
            cidVersion = options.cidVersion.toUInt(),
            rawLeaves = options.rawLeaves,
        )
        return FileLeafCidResult(
            leafCid = output.leafCid,
            tsize = output.tsize.toLong(),
        )
    }

    /** Callers may pass entries in any order; facade sorts lexicographically by name before Rust. */
    fun computeProofSetCid(
        entries: List<NamedBytes>,
        options: CidOptions = CidOptions.DEFAULT,
    ): ProofSetCidResult {
        ensureLoaded()
        val sorted = entries.sortedBy { it.name }
        val namedEntries = sorted.map { NamedEntry(name = it.name, bytes = it.bytes) }
        val output = uniffiComputeProofSetCid(
            entries = namedEntries,
            chunkSize = options.chunkSize.toUInt(),
            cidVersion = options.cidVersion.toUInt(),
            rawLeaves = options.rawLeaves,
            wrapWithDirectory = options.wrapWithDirectory,
            shardThreshold = options.shardThreshold,
            blockSizeLimit = options.blockSizeLimit ?: -1L,
        )
        return ProofSetCidResult(
            rootCid = output.rootCid,
            files = output.files,
            tsizes = output.tsizes.mapValues { (_, v) -> v.toLong() },
        )
    }

    /** Callers may pass entries in any order; facade sorts lexicographically by name before Rust. */
    fun computeProofSetCidFromLeaves(
        entries: List<NamedLeafCid>,
        options: CidOptions = CidOptions.DEFAULT,
    ): ProofSetCidResult {
        ensureLoaded()
        val sorted = entries.sortedBy { it.name }
        val precomputed = sorted.map {
            PrecomputedLeaf(
                name = it.name,
                leafCid = it.leafCid,
                tsize = it.tsize.toULong(),
            )
        }
        val output = uniffiComputeProofSetCidFromLeaves(
            entries = precomputed,
            wrapWithDirectory = options.wrapWithDirectory,
            shardThreshold = options.shardThreshold,
            blockSizeLimit = options.blockSizeLimit ?: -1L,
        )
        return ProofSetCidResult(
            rootCid = output.rootCid,
            files = output.files,
            tsizes = output.tsizes.mapValues { (_, v) -> v.toLong() },
        )
    }
}
