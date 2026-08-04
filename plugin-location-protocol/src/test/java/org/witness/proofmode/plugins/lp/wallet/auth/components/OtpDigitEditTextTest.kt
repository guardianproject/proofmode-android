package org.witness.proofmode.plugins.lp.wallet.auth.components

import android.content.Context
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class OtpDigitEditTextTest {
    @Test
    fun onBackspaceWhenEmptyFiresWhenEmpty() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val editText = OtpDigitEditText(context)
        var callbackFired = false
        editText.onBackspaceWhenEmpty = { callbackFired = true }

        val connection = editText.onCreateInputConnection(EditorInfo())
        assertNotNull(connection)

        // Trigger key event del when empty
        val deleteEvent = KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DEL)
        connection!!.sendKeyEvent(deleteEvent)
        assertTrue("Callback should fire on del event when empty", callbackFired)

        // Reset and trigger deleteSurroundingText when empty
        callbackFired = false
        connection.deleteSurroundingText(1, 0)
        assertTrue("Callback should fire on deleteSurroundingText when empty", callbackFired)
    }

    @Test
    fun onBackspaceWhenEmptyDoesNotFireWhenNotEmpty() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val editText = OtpDigitEditText(context)
        editText.setText("9")
        var callbackFired = false
        editText.onBackspaceWhenEmpty = { callbackFired = true }

        val connection = editText.onCreateInputConnection(EditorInfo())
        assertNotNull(connection)

        val deleteEvent = KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DEL)
        connection!!.sendKeyEvent(deleteEvent)
        assertFalse("Callback should NOT fire when not empty", callbackFired)

        connection.deleteSurroundingText(1, 0)
        assertFalse("Callback should NOT fire on deleteSurroundingText when not empty", callbackFired)
    }

    @Test
    fun normalTextInputWorks() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val editText = OtpDigitEditText(context)
        editText.setText("5")
        assertEquals("5", editText.text.toString())
    }
}
