package org.witness.proofmode.plugins.lp.wallet.auth.components

import android.content.ClipboardManager
import android.content.Context
import android.util.AttributeSet
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputConnectionWrapper
import androidx.appcompat.widget.AppCompatEditText

class OtpDigitEditText @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = android.R.attr.editTextStyle,
) : AppCompatEditText(context, attrs, defStyleAttr) {

    var onBackspaceWhenEmpty: (() -> Unit)? = null
    var onPaste: ((String) -> Unit)? = null

    override fun onTextContextMenuItem(id: Int): Boolean {
        if (id == android.R.id.paste) {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            val clip = clipboard?.primaryClip
            if (clip != null && clip.itemCount > 0) {
                val textValue = clip.getItemAt(0).text?.toString().orEmpty()
                if (textValue.length == 6 && textValue.all { it.isDigit() }) {
                    onPaste?.invoke(textValue)
                    return true
                }
            }
        }
        return super.onTextContextMenuItem(id)
    }

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection? {
        val baseConnection = super.onCreateInputConnection(outAttrs) ?: return null
        return BackspaceAwareInputConnection(baseConnection, true)
    }

    private inner class BackspaceAwareInputConnection(
        target: InputConnection,
        mutable: Boolean,
    ) : InputConnectionWrapper(target, mutable) {

        override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
            if (beforeLength == 1 && afterLength == 0 && this@OtpDigitEditText.text.isNullOrEmpty()) {
                onBackspaceWhenEmpty?.invoke()
                return false
            }
            return super.deleteSurroundingText(beforeLength, afterLength)
        }

        override fun sendKeyEvent(event: KeyEvent): Boolean {
            if (event.action == KeyEvent.ACTION_DOWN &&
                event.keyCode == KeyEvent.KEYCODE_DEL &&
                this@OtpDigitEditText.text.isNullOrEmpty()
            ) {
                onBackspaceWhenEmpty?.invoke()
                return false
            }
            return super.sendKeyEvent(event)
        }
    }
}
