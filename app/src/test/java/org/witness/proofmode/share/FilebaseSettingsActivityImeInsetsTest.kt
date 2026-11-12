package org.witness.proofmode.share

import android.view.View
import android.widget.ScrollView
import androidx.core.graphics.Insets
import androidx.core.view.WindowInsetsCompat
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.witness.proofmode.R
import org.witness.proofmode.TestProofModeApplication

@RunWith(RobolectricTestRunner::class)
// SDK 34, not the house 28 pin: below API 30 the platform WindowInsets has no ime() type, so the
// synthesized keyboard inset is dropped when it crosses ViewCompat's listener wrapper.
@Config(sdk = [34], application = TestProofModeApplication::class)
class FilebaseSettingsActivityImeInsetsTest {

    private fun launchScroll(): ScrollView {
        val activity = Robolectric.buildActivity(FilebaseSettingsActivity::class.java)
            .create().start().visible().get()
        return activity.findViewById(R.id.scroll_filebase_settings)
    }

    private fun dispatchIme(target: View, bottom: Int) {
        val insets = WindowInsetsCompat.Builder()
            .setInsets(WindowInsetsCompat.Type.ime(), Insets.of(0, 0, 0, bottom))
            .build()
        target.dispatchApplyWindowInsets(insets.toWindowInsets()!!)
    }

    @Test
    fun imeInset_padsScrollContainerBottom() {
        val scroll = launchScroll()

        dispatchIme(scroll, 800)

        assertEquals(800, scroll.paddingBottom)
    }
}
