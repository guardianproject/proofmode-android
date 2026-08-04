package org.witness.proofmode.cid

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CidLibInstrumentedTest {

    private val LEAF_SMALL_UTF8_CID = "bafkreiedbilurl7bwymun6xv67r6xzz473p6tswmgzvl4qcua4qovdaoom"
    private val PROOFSET_FLAT_CORE_ROOT = "bafybeieqzehoddjdkbj4u2w4vbg4xtjujjeoq2tdpi2vbx52b4sekyhinu"

    @Test
    fun computeFileCid_leafSmallUtf8() {
        val cid = CidLib.computeFileCid("hello proofmode".toByteArray(Charsets.UTF_8))
        assertEquals(LEAF_SMALL_UTF8_CID, cid)
    }

    @Test
    fun computeProofSetCid_flatCore_rootAndLeaves() {
        val entries = listOf(
            NamedBytes("a1b2c3d4e5f6.proof.csv", "col1,col2\nval1,val2\n".toByteArray()),
            NamedBytes("a1b2c3d4e5f6.proof.json", """{"k":"v"}""".toByteArray()),
            NamedBytes("a1b2c3d4e5f6.asc", "-----BEGIN PGP SIGNATURE-----\n".toByteArray()),
        )
        val result = CidLib.computeProofSetCid(entries)
        assertEquals(PROOFSET_FLAT_CORE_ROOT, result.rootCid)
        assertEquals(3, result.files.size)
        assertEquals(
            "bafkreidttqqbvxd7rxtovhhe7y7wffowa45oeh6cvmq7tqegk5nadcmtoy",
            result.files["a1b2c3d4e5f6.proof.csv"],
        )
    }

    @Test
    fun computeProofSetCid_lexOrder_independentOfInputOrder() {
        val forward = listOf(
            NamedBytes("a.proof.csv", byteArrayOf(1)),
            NamedBytes("b.proof.json", byteArrayOf(2)),
        )
        val reverse = forward.reversed()
        assertEquals(
            CidLib.computeProofSetCid(forward).rootCid,
            CidLib.computeProofSetCid(reverse).rootCid,
        )
    }
}
