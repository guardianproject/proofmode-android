package org.witness.proofmode.cid

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.witness.proofmode.cid.uniffi.CidException

@RunWith(AndroidJUnit4::class)
class CidLibFacadeErrorTest {

    @Test
    fun computeProofSetCid_wrapWithDirectoryTrue_throwsCidException() {
        val entries = listOf(NamedBytes("a.proof.csv", byteArrayOf(1)))
        val options = CidOptions.DEFAULT.copy(wrapWithDirectory = true)

        assertThrows(CidException.WrapWithDirectoryUnsupported::class.java) {
            CidLib.computeProofSetCid(entries, options)
        }
    }
}
