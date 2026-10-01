package org.witness.proofmode.share

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.view.View
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.witness.proofmode.FeatureFlags
import org.witness.proofmode.R
import org.witness.proofmode.TestProofModeApplication
import org.witness.proofmode.crypto.pgp.PgpUtils
import java.io.File
import java.io.FileInputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = TestProofModeApplication::class)
class ShareProofActivityShareOnlyChromeTest {
    @Test
    fun shareLayout_lacksFilebaseAndLpButtons() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        FeatureFlags.resetForTests(context)
        PgpUtils.init(context, "password")
        val mediaFile = File(context.cacheDir, "test-image.jpg").apply { writeText("x") }
        val mediaUri = Uri.fromFile(mediaFile)
        shadowOf(context.contentResolver).registerInputStream(mediaUri, FileInputStream(mediaFile))
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "image/jpeg"
            putExtra(Intent.EXTRA_STREAM, mediaUri)
        }
        val activity = Robolectric.buildActivity(ShareProofActivity::class.java, intent)
            .create().start().resume().get()

        // Positive control: proves activity_share inflated and findViewById resolves here, so the
        // absence assertions below cannot pass just because the lookup never ran.
        assertNotNull(
            "Share layout did not inflate; absence assertions would be vacuous",
            activity.findViewById<View>(R.id.view_proof),
        )

        assertShareControlAbsent(activity, "btn_upload_filebase")
        assertShareControlAbsent(activity, "ll_lp_attest_container")
        assertShareControlAbsent(activity, "btn_lp_onchain_attest")
        assertShareControlAbsent(activity, "btn_lp_offchain_attest")
    }

    @Test
    fun socialDialog_stillHasFilebaseCheckbox() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val view = android.view.LayoutInflater.from(context)
            .inflate(R.layout.dialog_share_social, null, false)
        assertNotNull(view.findViewById<View>(R.id.checkUploadToFilebase))
    }

    /**
     * A removed control is absent either because its id left the resource table entirely, or
     * because it survives in some other layout but is not in the Share hierarchy.
     */
    private fun assertShareControlAbsent(activity: android.app.Activity, idName: String) {
        val id = activity.resources.getIdentifier(idName, "id", activity.packageName)
        if (id == 0) return
        assertNull("$idName is still in the Share chrome", activity.findViewById<View>(id))
    }
}
