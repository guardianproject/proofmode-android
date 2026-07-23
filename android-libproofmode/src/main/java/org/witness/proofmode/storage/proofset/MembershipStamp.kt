package org.witness.proofmode.storage.proofset

import org.witness.proofmode.storage.filebase.FilebaseConfig

data class MembershipStamp(
    val uploadMode: FilebaseConfig.UploadMode,
    val mediaInclusion: MediaInclusion,
    val basenames: Set<String>,
)
