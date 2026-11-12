package org.witness.proofmode.util

import android.content.Context
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.core.graphics.Insets
import androidx.core.view.WindowInsetsCompat
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
// SDK 34, not the house 28 pin: below API 30 the platform WindowInsets has no ime() type, so the
// synthesized keyboard inset is dropped when it crosses ViewCompat's listener wrapper.
@Config(sdk = [34])
class ImeScrollPaddingTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
    }

    private fun imeInsets(bottom: Int): WindowInsetsCompat =
        WindowInsetsCompat.Builder()
            .setInsets(WindowInsetsCompat.Type.ime(), Insets.of(0, 0, 0, bottom))
            .build()

    private fun dispatchIme(target: View, bottom: Int): WindowInsetsCompat? {
        val result = target.dispatchApplyWindowInsets(imeInsets(bottom).toWindowInsets()!!)
        return WindowInsetsCompat.toWindowInsetsCompat(result, target)
    }

    private fun scrollWithBottomField(topSpacer: Int = 3000, bottomSpacer: Int = 0): Pair<ScrollView, EditText> {
        val field = EditText(context).apply { id = View.generateViewId() }
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(View(context), LinearLayout.LayoutParams(1080, topSpacer))
            addView(field, LinearLayout.LayoutParams(1080, 100))
            if (bottomSpacer > 0) addView(View(context), LinearLayout.LayoutParams(1080, bottomSpacer))
        }
        val scroll = ScrollView(context).apply {
            addView(content, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        return scroll to field
    }

    private fun layOut(scroll: ScrollView, width: Int, height: Int) {
        scroll.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY),
        )
        scroll.layout(0, 0, width, height)
    }

    @Test
    fun bind_addsImeBottomOnTopOfBasePadding() {
        val scroll = ScrollView(context).apply { setPadding(0, 0, 0, 16) }
        ImeScrollPadding.bind(scroll)

        val returned = dispatchIme(scroll, 400)!!
        assertEquals(416, scroll.paddingBottom)
        assertEquals(400, returned.getInsets(WindowInsetsCompat.Type.ime()).bottom)
        assertFalse(returned.isConsumed)

        dispatchIme(scroll, 0)
        assertEquals(16, scroll.paddingBottom)
    }

    @Test
    fun focusedFieldBelowFold_isScrolledAboveIme() {
        val (scroll, field) = scrollWithBottomField()
        layOut(scroll, 1080, 1000)
        ImeScrollPadding.bind(scroll)
        assertTrue(field.requestFocus())

        dispatchIme(scroll, 400)
        layOut(scroll, 1080, 1000)
        shadowOf(Looper.getMainLooper()).idle()

        val fieldBottom = field.top + field.height
        val visibleBottom = scroll.scrollY + scroll.height - scroll.paddingBottom
        assertTrue(
            "field bottom $fieldBottom is below the keyboard line $visibleBottom",
            fieldBottom <= visibleBottom,
        )
    }

    @Test
    fun focusedFieldAlreadyAboveIme_isNotScrolled() {
        val (scroll, field) = scrollWithBottomField(1500, 1500)
        layOut(scroll, 1080, 1000)
        ImeScrollPadding.bind(scroll)
        assertTrue(field.requestFocus())
        // Content y 1500..1600 sits at viewport y 500..600, well above a 400 px keyboard.
        scroll.scrollTo(0, 1000)

        dispatchIme(scroll, 400)

        assertEquals(1000, scroll.scrollY)
    }
}
