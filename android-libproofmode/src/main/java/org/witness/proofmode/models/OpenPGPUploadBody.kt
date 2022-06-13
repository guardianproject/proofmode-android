package org.witness.proofmode.models

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable


/**
 * The request body for publishing a key to https://keys.openpgp.org/
 */
@Serializable
data class OpenPGPUploadBody(

    @SerialName("keytext") val key:String
)
