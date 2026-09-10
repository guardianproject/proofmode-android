package org.witness.proofmode.plugins.lp.wallet.auth

import android.app.Activity
import android.view.View
import android.view.WindowManager
import com.google.android.material.bottomsheet.BottomSheetDialog
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class AuthSheetImeInsetsTest {

    @Test
    fun bind_resizesForIme_withoutEdgeToEdge() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val dialog = BottomSheetDialog(activity)
        dialog.show()
        val target = View(activity)
        AuthSheetImeInsets.bind(dialog, target)
        val window = dialog.window!!
        assertEquals(
            WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE,
            window.attributes.softInputMode and WindowManager.LayoutParams.SOFT_INPUT_MASK_ADJUST,
        )
        dialog.dismiss()
    }
}
