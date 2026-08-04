package org.witness.proofmode.share

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.view.View
import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.witness.proofmode.FeatureFlags
import org.witness.proofmode.ProofMode
import org.witness.proofmode.R
import org.witness.proofmode.TestProofModeApplication
import org.witness.proofmode.crypto.pgp.PgpUtils
import java.io.File
import java.io.FileInputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = TestProofModeApplication::class)
class ShareProofActivityLpAttestVisibilityTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences(FeatureFlags.PREFS_NAME, Context.MODE_PRIVATE)
            .edit().clear().commit()
        PreferenceManager.getDefaultSharedPreferences(context).edit().clear().commit()
        FeatureFlags.resetForTests(context)
        PgpUtils.init(context, "password")
    }

    @Test
    fun onResume_sameIntentKey_masterOff_hidesLpAttestContainerDespiteEarlyReturn() {
        FeatureFlags.lpEnabled = true
        PreferenceManager.getDefaultSharedPreferences(context)
            .edit().putBoolean(ProofMode.PREF_OPTION_LOCATION, true).commit()

        val mediaFile = File(context.cacheDir, "test-image.jpg").apply {
            writeText("fake image bytes")
        }
        val mediaUri = Uri.fromFile(mediaFile)
        shadowOf(context.contentResolver).registerInputStream(mediaUri, FileInputStream(mediaFile))
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "image/jpeg"
            putExtra(Intent.EXTRA_STREAM, mediaUri)
        }

        val controller = Robolectric.buildActivity(ShareProofActivity::class.java, intent)
            .create()
            .start()
            .resume()
        val activity = controller.get()

        assertEquals(
            View.VISIBLE,
            activity.findViewById<View>(R.id.ll_lp_attest_container).visibility,
        )

        PreferenceManager.getDefaultSharedPreferences(context)
            .edit().putBoolean(ProofMode.PREF_OPTION_LOCATION, false).commit()

        controller.pause().resume()

        assertEquals(
            View.GONE,
            activity.findViewById<View>(R.id.ll_lp_attest_container).visibility,
        )
    }
}
