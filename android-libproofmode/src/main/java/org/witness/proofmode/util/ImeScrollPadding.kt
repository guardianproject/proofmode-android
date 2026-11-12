package org.witness.proofmode.util

import android.graphics.Rect
import android.widget.ScrollView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding

object ImeScrollPadding {

    /**
     * Pads [scroll] by the soft keyboard's height so every field stays reachable, and keeps the
     * focused field above the keyboard. Under edge-to-edge the window no longer resizes for the
     * keyboard, so each scroll container has to do this itself. Call after the layout is inflated:
     * the padding present at bind time is the base the keyboard height is added to.
     */
    fun bind(scroll: ScrollView) {
        val basePaddingBottom = scroll.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(scroll) { view, insets ->
            val imeBottom = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
            view.updatePadding(bottom = basePaddingBottom + imeBottom)
            revealFocusedField(scroll)
            // Not consumed: siblings such as the AppBarLayout still need these insets.
            insets
        }
        ViewCompat.requestApplyInsets(scroll)
    }

    /**
     * The focus-triggered scroll runs before the keyboard's inset arrives, and ScrollView's own
     * rect-on-screen scroll ignores bottom padding, so it would park the field under the keyboard.
     * Scroll by hand against the padded viewport instead.
     */
    private fun revealFocusedField(scroll: ScrollView) {
        val focused = scroll.findFocus() ?: return
        val rect = Rect()
        focused.getDrawingRect(rect)
        scroll.offsetDescendantRectToMyCoords(focused, rect)
        // The rect is in content coordinates, so compare it against the viewport shifted by scrollY.
        val visibleTop = scroll.scrollY + scroll.paddingTop
        val visibleBottom = scroll.scrollY + scroll.height - scroll.paddingBottom
        when {
            rect.bottom > visibleBottom -> scroll.scrollBy(0, rect.bottom - visibleBottom)
            rect.top < visibleTop -> scroll.scrollBy(0, rect.top - visibleTop)
        }
    }
}
