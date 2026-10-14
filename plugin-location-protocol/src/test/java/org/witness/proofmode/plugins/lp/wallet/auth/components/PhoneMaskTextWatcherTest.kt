package org.witness.proofmode.plugins.lp.wallet.auth.components

import android.content.Context
import android.widget.EditText
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class PhoneMaskTextWatcherTest {

    private fun field(): Pair<EditText, PhoneMaskTextWatcher> {
        val et = EditText(ApplicationProvider.getApplicationContext<Context>())
        val watcher = PhoneMaskTextWatcher()
        et.addTextChangedListener(watcher)
        return et to watcher
    }

    @Test
    fun typingDigitByDigit_rendersMask() {
        val (et, w) = field()
        "4155550123".forEachIndexed { i, c ->
            et.text.append(c)
            assertEquals(PhoneEntry.masked("4155550123".take(i + 1)), et.text.toString())
        }
        assertEquals("4155550123", w.digits)
        assertEquals(et.text.length, et.selectionEnd)
    }

    @Test
    fun nonDigit_leavesTextUnchanged() {
        val (et, _) = field()
        et.setText("415")
        et.text.append('*')
        assertEquals("(415", et.text.toString())
    }

    @Test
    fun plusOnePaste_rendersFullMask() {
        val (et, w) = field()
        et.setText("+1 (415) 555-0123")
        assertEquals("(415) 555-0123", et.text.toString())
        assertEquals("4155550123", w.digits)
    }

    @Test
    fun otherCountryPaste_keepsPreviousText() {
        val (et, w) = field()
        et.setText("415")
        et.setText("+44 7700 900123")
        assertEquals("(415", et.text.toString())
        assertEquals("415", w.digits)
    }

    @Test
    fun eleventhDigit_isIgnored() {
        val (et, _) = field()
        et.setText("4155550123")
        et.text.append('9')
        assertEquals("(415) 555-0123", et.text.toString())
    }

    @Test
    fun deletingAfterSeparator_dropsSeparator() {
        val (et, w) = field()
        et.setText("41555") // "(415) 55"
        et.text.delete(et.text.length - 1, et.text.length) // "(415) 5"
        et.text.delete(et.text.length - 1, et.text.length) // digits "415"
        assertEquals("(415", et.text.toString())
        assertEquals("415", w.digits)
    }

    @Test
    fun clearing_emptiesDigits() {
        val (et, w) = field()
        et.setText("415")
        et.setText("")
        assertEquals("", et.text.toString())
        assertEquals("", w.digits)
    }
}
