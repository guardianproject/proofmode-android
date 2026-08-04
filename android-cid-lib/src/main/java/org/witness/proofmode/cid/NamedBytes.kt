package org.witness.proofmode.cid

data class NamedBytes(val name: String, val bytes: ByteArray) {
    override fun equals(other: Any?): Boolean =
        other is NamedBytes && name == other.name && bytes.contentEquals(other.bytes)

    override fun hashCode(): Int = 31 * name.hashCode() + bytes.contentHashCode()
}
