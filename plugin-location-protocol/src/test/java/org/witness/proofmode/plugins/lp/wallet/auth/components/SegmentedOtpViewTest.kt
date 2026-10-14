package org.witness.proofmode.plugins.lp.wallet.auth.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.view.View
import android.widget.EditText
import android.widget.TextView
import androidx.fragment.app.FragmentActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowPopupMenu
import org.witness.proofmode.plugins.lp.R

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class SegmentedOtpViewTest {

    private fun view(): SegmentedOtpView {
        val activity = Robolectric.buildActivity(FragmentActivity::class.java).setup().get()
        return SegmentedOtpView(activity).also { activity.setContentView(it) }
    }

    private fun field(v: SegmentedOtpView): EditText = v.findViewById(R.id.et_otp_code)

    private fun resId(v: View, name: String): Int =
        v.context.resources.getIdentifier(name, "id", v.context.packageName)

    private fun digit(v: SegmentedOtpView, i: Int): String =
        v.findViewById<TextView>(resId(v, "otp_digit_$i")).text.toString()

    private fun caret(v: SegmentedOtpView, i: Int): View = v.findViewById(resId(v, "otp_caret_$i"))

    @Test
    fun pastedWithSpace_givesCodeAndFiresOnce() {
        val v = view(); var fired = 0; v.onCodeComplete = { fired += 1 }
        field(v).setText("123 456")
        assertEquals("123456", v.getCode()); assertEquals(1, fired)
        assertEquals("123456", field(v).text.toString())
        (0..5).forEach { assertEquals("${it + 1}", digit(v, it)) }
    }

    @Test
    fun settingSixDigitsAtOnce_firesCompleteOnce() {
        val v = view(); var fired = 0; v.onCodeComplete = { fired += 1 }
        field(v).setText("654321"); assertEquals(1, fired); assertEquals("654321", v.getCode())
    }

    @Test
    fun sevenDigits_capsAtSix() {
        val v = view(); field(v).setText("1234567"); assertEquals("123456", v.getCode())
    }

    @Test
    fun fewerThanSix_doesNotFire() {
        val v = view(); var fired = 0; v.onCodeComplete = { fired += 1 }
        field(v).setText("12345"); assertEquals(0, fired); assertEquals("", digit(v, 5))
    }

    @Test
    fun deletingThenRetypingSixthDigit_firesCompleteAgain() {
        val v = view(); var fired = 0; v.onCodeComplete = { fired += 1 }
        field(v).setText("123456"); field(v).setText("12345"); field(v).setText("123456")
        assertEquals(2, fired)
    }

    @Test
    fun clear_emptiesAndRearms() {
        val v = view(); var fired = 0; v.onCodeComplete = { fired += 1 }
        field(v).setText("123456"); v.clear()
        assertEquals("", v.getCode()); assertEquals("", field(v).text.toString())
        field(v).setText("123456"); assertEquals(2, fired)
    }

    @Test
    fun completionCallbackMayClearSynchronously() {
        val v = view(); var fired = 0; v.onCodeComplete = { fired++; v.clear() }
        field(v).setText("123456"); field(v).setText("123456")
        assertEquals(2, fired); assertEquals("", v.getCode())
    }

    @Test
    fun setCode_sanitizesAndFires() {
        val v = view(); var fired = 0; v.onCodeComplete = { fired += 1 }
        v.setCode(" 12-34 56 "); assertEquals("123456", v.getCode()); assertEquals(1, fired)
    }

    @Test
    fun activeBox_followsCodeLengthWhileFocused() {
        val v = view(); field(v).requestFocus(); field(v).setText("12")
        assertEquals(View.VISIBLE, v.findViewById<View>(R.id.otp_caret_2).visibility)
        assertEquals(View.GONE, v.findViewById<View>(R.id.otp_caret_1).visibility)
        field(v).setText("123456"); (0..5).forEach { assertEquals(View.GONE, caret(v, it).visibility) }
    }

    @Test
    fun selection_isForcedToEnd() {
        val v = view(); field(v).setText("1234"); field(v).setSelection(1)
        assertEquals(4, field(v).selectionStart); assertEquals(4, field(v).selectionEnd)
    }

    @Test
    fun pasteOverPartialCode_replacesIt() {
        val v = view(); val completed = mutableListOf<String>(); v.onCodeComplete = { completed += it }
        field(v).setText("12"); field(v).text.append("654321")
        assertEquals("654321", v.getCode()); assertEquals(listOf("654321"), completed)
    }

    @Test
    fun pasteWithSurroundingText_replacesPartialCode() {
        val v = view(); field(v).setText("12"); field(v).text.append("Your code is 654321")
        assertEquals("654321", v.getCode())
    }

    @Test
    fun typingSingleDigits_stillAppends() {
        val v = view(); field(v).setText("12"); field(v).text.append("3")
        assertEquals("123", v.getCode())
    }

    @Test
    fun longPress_withClipboardText_pastesIntoField() {
        val v = view(); val completed = mutableListOf<String>(); v.onCodeComplete = { completed += it }
        val clipboard = v.context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("code", "654321"))
        ShadowPopupMenu.setLatestPopupMenu(null)

        assertTrue(field(v).performLongClick())

        val popup = ShadowPopupMenu.getLatestPopupMenu()
        assertNotNull("long-press must show the paste menu", popup)
        assertEquals(1, popup.menu.size())
        val paste = popup.menu.getItem(0)
        assertEquals(v.context.getString(android.R.string.paste), paste.title.toString())
        Shadows.shadowOf(popup).onMenuItemClickListener.onMenuItemClick(paste)
        assertEquals("654321", v.getCode()); assertEquals(listOf("654321"), completed)
    }

    @Test
    fun longPress_withEmptyClipboard_showsNoPasteMenu() {
        val v = view()
        val clipboard = v.context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("code", ""))
        ShadowPopupMenu.setLatestPopupMenu(null)

        field(v).performLongClick()

        assertNull(ShadowPopupMenu.getLatestPopupMenu())
    }

    @Test
    fun focusFirst_focusesHiddenField() {
        val v = view(); v.focusFirst(); assertTrue(field(v).isFocused)
    }
}
