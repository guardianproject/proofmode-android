package org.witness.proofmode.share

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.witness.proofmode.R
import org.witness.proofmode.TestProofModeApplication

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = TestProofModeApplication::class)
class FilebaseFailureMessagesContextTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
    }

    @Test
    fun failures_useTheirResource() {
        val cases = listOf(
            null to R.string.filebase_failure_same_policy,
            "Upload failed: 404 body=<Error><Code>NoSuchBucket</Code></Error>" to R.string.filebase_failure_credentials,
            "IPFS RPC upload failed: 401" to R.string.filebase_failure_ipfs_credentials,
        )
        for ((raw, res) in cases) {
            assertEquals("raw=$raw", context.getString(res), formatFilebaseFailureMessage(context, raw))
        }
    }
}
