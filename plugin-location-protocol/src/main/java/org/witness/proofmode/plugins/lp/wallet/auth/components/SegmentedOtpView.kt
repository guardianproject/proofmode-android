package org.witness.proofmode.plugins.lp.wallet.auth.components

import android.content.ClipboardManager
import android.content.Context
import android.text.Editable
import android.text.Selection
import android.text.TextWatcher
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import android.widget.PopupMenu
import android.widget.TextView
import org.witness.proofmode.plugins.lp.R

/**
 * Six display-only boxes driven by one hidden [OtpCodeEditText] that owns the text, so paste,
 * keyboard clipboard chips and SMS autofill all arrive as a single edit (port of iOS OTPCodeField).
 */
class SegmentedOtpView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : FrameLayout(context, attrs, defStyleAttr) {

    var onCodeComplete: ((String) -> Unit)? = null

    private val codeField: OtpCodeEditText
    private val boxes: List<View>
    private val digits: List<TextView>
    private val carets: List<View>
    private var previousLength = 0
    private var rewriting = false
    private var insertedChunk: String? = null

    init {
        LayoutInflater.from(context).inflate(R.layout.view_segmented_otp, this, true)
        codeField = findViewById(R.id.et_otp_code)
        boxes = listOf(
            findViewById(R.id.otp_box_0), findViewById(R.id.otp_box_1), findViewById(R.id.otp_box_2),
            findViewById(R.id.otp_box_3), findViewById(R.id.otp_box_4), findViewById(R.id.otp_box_5),
        )
        digits = listOf(
            findViewById(R.id.otp_digit_0), findViewById(R.id.otp_digit_1), findViewById(R.id.otp_digit_2),
            findViewById(R.id.otp_digit_3), findViewById(R.id.otp_digit_4), findViewById(R.id.otp_digit_5),
        )
        carets = listOf(
            findViewById(R.id.otp_caret_0), findViewById(R.id.otp_caret_1), findViewById(R.id.otp_caret_2),
            findViewById(R.id.otp_caret_3), findViewById(R.id.otp_caret_4), findViewById(R.id.otp_caret_5),
        )
        codeField.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                insertedChunk = if (s != null && count > 1) s.subSequence(start, start + count).toString() else null
            }
            override fun afterTextChanged(s: Editable) = onTextEdited(s)
        })
        codeField.setOnFocusChangeListener { _, _ -> render(getCode()) }
        codeField.setOnLongClickListener { showPasteMenu() }
        render("")
    }

    fun getCode(): String = OtpDigits.sanitize(codeField.text?.toString().orEmpty())

    /** Sanitizes [code]; fires [onCodeComplete] on a <6 → 6 transition, exactly as typing does. */
    fun setCode(code: String) {
        codeField.setText(code)
    }

    fun clear() {
        codeField.setText("")
        focusFirst()
    }

    fun focusFirst() {
        codeField.requestFocus()
    }

    private fun onTextEdited(s: Editable) {
        if (rewriting) return
        // A multi-character insert (paste, clipboard chip, IME suggestion) holding a whole code replaces
        // whatever partial code was already there instead of being appended to it.
        val chunkCode = insertedChunk?.let { OtpDigits.sanitize(it) }
        insertedChunk = null
        val sanitized = if (chunkCode?.length == OtpDigits.LENGTH) chunkCode else OtpDigits.sanitize(s.toString())
        if (sanitized != s.toString()) {
            rewriting = true
            try {
                s.replace(0, s.length, sanitized)
                Selection.setSelection(s, s.length)
            } finally {
                rewriting = false
            }
        }
        render(sanitized)
        // Record the length before invoking the callback so a clear() from inside it re-arms completion.
        val completes = previousLength < OtpDigits.LENGTH && sanitized.length == OtpDigits.LENGTH
        previousLength = sanitized.length
        if (completes) onCodeComplete?.invoke(sanitized)
    }

    /**
     * The hidden field has no visible cursor, so the platform long-press never offers Paste;
     * show a one-item Paste menu ourselves when the clipboard holds text.
     */
    private fun showPasteMenu(): Boolean {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return false
        val clip = clipboard.primaryClip ?: return false
        val hasText = (0 until clip.itemCount).any { !clip.getItemAt(it).coerceToText(context).isNullOrBlank() }
        if (!hasText) return false
        PopupMenu(context, codeField).apply {
            menu.add(context.getString(android.R.string.paste))
            setOnMenuItemClickListener { codeField.onTextContextMenuItem(android.R.id.paste) }
            show()
        }
        return true
    }

    private fun render(code: String) {
        val active = OtpActiveBox.index(code.length, codeField.isFocused)
        for (i in 0 until OtpDigits.LENGTH) {
            digits[i].text = code.getOrNull(i)?.toString() ?: ""
            boxes[i].setBackgroundResource(
                if (i == active) R.drawable.otp_digit_focused_background else R.drawable.otp_digit_background
            )
            carets[i].visibility = if (i == active) View.VISIBLE else View.GONE
        }
    }
}
