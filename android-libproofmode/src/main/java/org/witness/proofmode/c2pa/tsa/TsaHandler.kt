package org.witness.proofmode.c2pa.tsa

import java.io.IOException

/** What a [LocalTsaServer] does with the body of a time-stamp request. */
fun interface TsaHandler {
    /**
     * Turns a DER `TimeStampReq` into a DER `TimeStampResp`. Throws [IOException]
     * when no reply could be obtained at all; the server answers 502 so c2pa-rs
     * fails the timestamp rather than embedding garbage.
     */
    @Throws(IOException::class)
    fun handle(query: ByteArray): ByteArray
}
