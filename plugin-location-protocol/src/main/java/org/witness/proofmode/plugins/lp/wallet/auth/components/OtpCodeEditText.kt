package org.witness.proofmode.plugins.lp.wallet.auth.components

import android.content.Context
import android.util.AttributeSet
import androidx.appcompat.widget.AppCompatEditText

class OtpCodeEditText @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = android.R.attr.editTextStyle,
) : AppCompatEditText(context, attrs, defStyleAttr) {

    override fun onSelectionChanged(selStart: Int, selEnd: Int) {
        super.onSelectionChanged(selStart, selEnd)
        // The super constructor can call this before Kotlin fields exist, so no field access here.
        val length = text?.length ?: return
        if (selStart != length || selEnd != length) setSelection(length)
    }
}
