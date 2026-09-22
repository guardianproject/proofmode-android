package org.witness.proofmode

import android.content.DialogInterface
import android.os.Looper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlertDialog
import org.witness.proofmode.filebase.FilebaseOnDemandUiEvent
import org.witness.proofmode.share.FilebaseSettingsActivity

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = TestProofModeApplication::class)
class MainActivityFilebaseDialogHostTest {

    @Test
    fun reconfigure_showsDialog_positiveStartsFilebaseSettings() {
        val controller = Robolectric.buildActivity(android.app.Activity::class.java).setup()
        val activity = controller.get()

        showFilebaseOnDemandDialog(
            activity,
            FilebaseOnDemandUiEvent.Reconfigure("reconfigure-me"),
        )
        shadowOf(Looper.getMainLooper()).idle()

        val dialog = ShadowAlertDialog.getLatestAlertDialog()
        assertNotNull(dialog)
        assertTrue(dialog.isShowing)
        assertEquals(
            activity.getString(R.string.filebase_upload_failed),
            Shadows.shadowOf(dialog).title.toString(),
        )
        assertEquals("reconfigure-me", Shadows.shadowOf(dialog).message.toString())
        assertEquals(
            activity.getString(R.string.filebase_reconfigure),
            dialog.getButton(DialogInterface.BUTTON_POSITIVE).text.toString(),
        )

        dialog.getButton(DialogInterface.BUTTON_POSITIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()

        val started = Shadows.shadowOf(activity).nextStartedActivity
        assertNotNull(started)
        assertEquals(FilebaseSettingsActivity::class.java.name, started.component?.className)
    }

    @Test
    fun dismissibleFailure_showsOkDialog_doesNotStartSettings() {
        val controller = Robolectric.buildActivity(android.app.Activity::class.java).setup()
        val activity = controller.get()

        showFilebaseOnDemandDialog(
            activity,
            FilebaseOnDemandUiEvent.DismissibleFailure("socket timeout"),
        )
        shadowOf(Looper.getMainLooper()).idle()

        val dialog = ShadowAlertDialog.getLatestAlertDialog()
        assertNotNull(dialog)
        assertTrue(dialog.isShowing)
        assertEquals("socket timeout", Shadows.shadowOf(dialog).message.toString())
        assertEquals(
            activity.getString(android.R.string.ok),
            dialog.getButton(DialogInterface.BUTTON_POSITIVE).text.toString(),
        )

        dialog.getButton(DialogInterface.BUTTON_POSITIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertNull(Shadows.shadowOf(activity).nextStartedActivity)
    }

    @Test
    fun finishingActivity_doesNotShowDialog() {
        val controller = Robolectric.buildActivity(android.app.Activity::class.java).setup()
        val activity = controller.get()
        activity.finish()

        showFilebaseOnDemandDialog(
            activity,
            FilebaseOnDemandUiEvent.Reconfigure("should-not-show"),
        )

        assertNull(ShadowAlertDialog.getLatestAlertDialog())
    }
}
