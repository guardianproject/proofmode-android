package org.witness.proofmode.models

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class OpenPGPPublicKeyResponse(
    @SerialName("key_fpr")
    val fingerprintKey:String,
    val token:String,
    val status:Map<String,String>
)
