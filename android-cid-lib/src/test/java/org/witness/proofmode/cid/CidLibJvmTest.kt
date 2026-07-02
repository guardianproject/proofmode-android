package org.witness.proofmode.cid

import org.junit.Assume.assumeNoException
import org.junit.Test

class CidLibJvmTest {
    @Test
    fun computeFileCid_skippedWhenNativeUnavailable() {
        try {
            CidLib.computeFileCid(byteArrayOf(1))
        } catch (e: UnsatisfiedLinkError) {
            assumeNoException(e)
        }
    }
}
