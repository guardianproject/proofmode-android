package org.witness.proofmode.plugins.lp.wallet.auth.components

import android.content.Context
import android.text.Editable
import android.text.TextWatcher
import android.util.AttributeSet
import android.view.LayoutInflater
import android.widget.LinearLayout
import org.witness.proofmode.plugins.lp.R

class SegmentedOtpView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : LinearLayout(context, attrs, defStyleAttr) {

    companion object {
        private const val DIGIT_COUNT = 6
    }

    var onCodeComplete: ((String) -> Unit)? = null

    private val digitFields: List<OtpDigitEditText>

    init {
        LayoutInflater.from(context).inflate(R.layout.view_segmented_otp, this, true)
        digitFields = listOf(
            findViewById(R.id.otp_digit_0),
            findViewById(R.id.otp_digit_1),
            findViewById(R.id.otp_digit_2),
            findViewById(R.id.otp_digit_3),
            findViewById(R.id.otp_digit_4),
            findViewById(R.id.otp_digit_5),
        )
        setupDigitFields()
    }

    fun getCode(): String = digitFields.joinToString("") {
        it.text?.toString().orEmpty()
    }

    fun clear() {
        digitFields.forEach { it.setText("") }
        digitFields.firstOrNull()?.requestFocus()
    }

    fun focusFirst() {
        digitFields.firstOrNull()?.requestFocus()
    }

    private fun setupDigitFields() {
        digitFields.forEachIndexed { index, field ->
            // Focus styling
            field.setOnFocusChangeListener { _, hasFocus ->
                field.setBackgroundResource(
                    if (hasFocus) R.drawable.otp_digit_focused_background
                    else R.drawable.otp_digit_background
                )
            }

            // Auto-advance on digit entry
            field.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
                override fun afterTextChanged(s: Editable?) {
                    val text = s?.toString().orEmpty()
                    if (text.length == 1 && index < DIGIT_COUNT - 1) {
                        digitFields[index + 1].requestFocus()
                    }
                    checkComplete()
                }
            })

            // Reverse-focus on backspace when empty
            field.onBackspaceWhenEmpty = {
                if (index > 0) {
                    val prev = digitFields[index - 1]
                    prev.setText("")
                    prev.requestFocus()
                }
            }
        }

        // Handle paste of full 6-digit code
        digitFields.first().onPaste = { pasted ->
            distributePastedCode(pasted)
        }
    }

    private fun distributePastedCode(code: String) {
        code.forEachIndexed { index, char ->
            if (index < digitFields.size) {
                digitFields[index].setText(char.toString())
            }
        }
        digitFields.lastOrNull()?.requestFocus()
        checkComplete()
    }

    private fun checkComplete() {
        val code = getCode()
        if (code.length == DIGIT_COUNT && code.all { it.isDigit() }) {
            onCodeComplete?.invoke(code)
        }
    }
}
