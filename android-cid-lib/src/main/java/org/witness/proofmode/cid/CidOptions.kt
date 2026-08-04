package org.witness.proofmode.cid

data class CidOptions(
    val chunkSize: Int = DEFAULT_CHUNK_SIZE,
    val cidVersion: Int = DEFAULT_CID_VERSION,
    val rawLeaves: Boolean = DEFAULT_RAW_LEAVES,
    val wrapWithDirectory: Boolean = DEFAULT_WRAP_WITH_DIRECTORY,
    val shardThreshold: Long = DEFAULT_SHARD_THRESHOLD,
    val blockSizeLimit: Long? = null,
) {
    companion object {
        const val DEFAULT_CHUNK_SIZE = 262_144
        const val DEFAULT_CID_VERSION = 1
        const val DEFAULT_RAW_LEAVES = true
        const val DEFAULT_WRAP_WITH_DIRECTORY = false
        const val DEFAULT_SHARD_THRESHOLD = 262_144L
        val DEFAULT = CidOptions()
    }
}
