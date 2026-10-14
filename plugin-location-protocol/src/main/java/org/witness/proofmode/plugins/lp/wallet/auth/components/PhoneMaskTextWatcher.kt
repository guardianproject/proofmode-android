package org.witness.proofmode.plugins.lp.wallet.auth.components

import android.text.Editable
import android.text.Selection
import android.text.TextWatcher

/**
 * Formats a phone field live as `(___) ___-____`.
 *
 * Rewrites the [Editable] it is given, so it holds no reference to the `EditText`.
 * An edit rejected by [PhoneEntry.nationalDigits] restores the mask of the previous [digits].
 */
class PhoneMaskTextWatcher : TextWatcher {

    /** Current national digits (no country code, at most 10), `""` initially. */
    var digits: String = ""
        private set

    private var editing = false

    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit

    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit

    override fun afterTextChanged(s: Editable) {
        if (editing) return
        val next = PhoneEntry.nationalDigits(s.toString())
        val target = PhoneEntry.masked(next ?: digits)
        if (next != null) digits = next
        if (s.toString() != target) {
            editing = true
            try {
                s.replace(0, s.length, target)
                Selection.setSelection(s, s.length)
            } finally {
                editing = false
            }
        }
    }
}
