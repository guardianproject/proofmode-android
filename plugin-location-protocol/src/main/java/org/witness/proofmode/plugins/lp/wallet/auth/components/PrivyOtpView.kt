package org.witness.proofmode.plugins.lp.wallet.auth.components

import android.content.Context
import android.os.Handler
import android.os.Looper

import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.witness.proofmode.plugins.lp.R

class PrivyOtpView(context: Context, attrs: AttributeSet? = null) : FrameLayout(context, attrs) {

    var identifier: String
        get() = identifierEditText.text?.toString().orEmpty()
        set(value) {
            identifierEditText.setText(value)
        }

    var code: String
        get() = segmentedOtpView.getCode()
        set(value) {
            segmentedOtpView.clear()
        }

    var codeSent: Boolean = false
        set(value) {
            field = value
            updateStepVisibility()
        }

    var identifierLabel: String = "Email Address"
        set(value) {
            field = value
            identifierLabelTextView.text = value
        }

    var codeLabel: String = "Verification Code"
        set(value) {
            field = value
            codeLabelTextView.text = value
        }

    var onSendCode: (suspend (identifier: String) -> Boolean)? = null
    var onVerifyCode: (suspend (code: String, identifier: String) -> Boolean)? = null
    var onComplete: ((error: String?) -> Unit)? = null

    private var viewScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val uiHandler = Handler(Looper.getMainLooper())

    private val step1Layout: View
    private val step2Layout: View
    private val identifierLabelTextView: TextView
    private val codeLabelTextView: TextView
    private val identifierEditText: EditText
    private val segmentedOtpView: SegmentedOtpView
    private val sendButton: Button
    private val verifyButton: Button
    private val step1ErrorLayout: LinearLayout
    private val step2ErrorLayout: LinearLayout
    private val step1ErrorTextView: TextView
    private val step2ErrorTextView: TextView
    private val dismissStep1ErrorButton: ImageButton
    private val dismissStep2ErrorButton: ImageButton
    private val step1LoadingOverlay: View
    private val step2LoadingOverlay: View

    private var step1ErrorDismissRunnable: Runnable? = null
    private var step2ErrorDismissRunnable: Runnable? = null
    private var sendingCodeJob: Job? = null
    private var verifyingCodeJob: Job? = null

    init {
        LayoutInflater.from(context).inflate(R.layout.view_privy_otp, this, true)

        step1Layout = findViewById(R.id.layout_step1)
        step2Layout = findViewById(R.id.layout_step2)
        identifierLabelTextView = findViewById(R.id.tv_identifier_label)
        codeLabelTextView = findViewById(R.id.tv_code_label)
        identifierEditText = findViewById(R.id.et_identifier)
        segmentedOtpView = findViewById(R.id.et_code)
        sendButton = findViewById(R.id.btn_send_code)
        verifyButton = findViewById(R.id.btn_verify)
        step1ErrorLayout = findViewById(R.id.layout_step1_error)
        step2ErrorLayout = findViewById(R.id.layout_step2_error)
        step1ErrorTextView = findViewById(R.id.tv_step1_error)
        step2ErrorTextView = findViewById(R.id.tv_step2_error)
        dismissStep1ErrorButton = findViewById(R.id.btn_dismiss_step1_error)
        dismissStep2ErrorButton = findViewById(R.id.btn_dismiss_step2_error)
        step1LoadingOverlay = findViewById(R.id.overlay_step1_loading)
        step2LoadingOverlay = findViewById(R.id.overlay_step2_loading)

        bindListeners()
        identifierLabelTextView.text = identifierLabel
        codeLabelTextView.text = codeLabel
        updateStepVisibility()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        sendingCodeJob?.cancel()
        verifyingCodeJob?.cancel()
        clearStep1Error()
        clearStep2Error()
        viewScope.cancel()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (!viewScope.isActive()) {
            viewScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        }
    }

    private fun bindListeners() {
        sendButton.setOnClickListener {
            onSendCodeClicked()
        }

        verifyButton.setOnClickListener {
            onVerifyCodeClicked()
        }

        dismissStep1ErrorButton.setOnClickListener {
            clearStep1Error()
        }

        dismissStep2ErrorButton.setOnClickListener {
            clearStep2Error()
        }

        segmentedOtpView.onCodeComplete = { code ->
            if (codeSent && verifyingCodeJob?.isActive != true) {
                onVerifyCodeClicked()
            }
        }
    }

    private fun updateStepVisibility() {
        step1Layout.visibility = if (codeSent) View.GONE else View.VISIBLE
        step2Layout.visibility = if (codeSent) View.VISIBLE else View.GONE

        if (codeSent) {
            clearStep1Error()
            segmentedOtpView.focusFirst()
        } else {
            clearStep2Error()
            focusAndShowKeyboard(identifierEditText)
        }
    }

    private fun onSendCodeClicked() {
        if (sendingCodeJob?.isActive == true) {
            return
        }

        val currentIdentifier = identifier.trim()
        if (currentIdentifier.isBlank()) {
            showStep1Error("Identifier is required")
            onComplete?.invoke("Identifier is required")
            return
        }

        val sendCodeCallback = onSendCode
        if (sendCodeCallback == null) {
            showStep1Error("Send code action is unavailable")
            onComplete?.invoke("Send code action is unavailable")
            return
        }

        clearStep1Error()
        setStep1Loading(isLoading = true)

        sendingCodeJob = viewScope.launch {
            try {
                val sent = sendCodeCallback.invoke(currentIdentifier)
                if (sent) {
                    identifier = currentIdentifier
                    codeSent = true
                } else {
                    val message = "Unable to send code"
                    showStep1Error(message)
                    onComplete?.invoke(message)
                }
            } catch (t: Throwable) {
                val message = t.message ?: "Something went wrong while sending code"
                showStep1Error(message)
                onComplete?.invoke(message)
            } finally {
                setStep1Loading(isLoading = false)
            }
        }
    }

    private fun onVerifyCodeClicked() {
        if (verifyingCodeJob?.isActive == true) {
            return
        }

        val currentCode = code.trim()
        if (currentCode.length != 6) {
            showStep2Error("Enter the 6-digit verification code")
            onComplete?.invoke("Enter the 6-digit verification code")
            return
        }

        val verifyCodeCallback = onVerifyCode
        if (verifyCodeCallback == null) {
            showStep2Error("Verify action is unavailable")
            onComplete?.invoke("Verify action is unavailable")
            return
        }

        clearStep2Error()
        setStep2Loading(isLoading = true)

        val currentIdentifier = identifier.trim()
        verifyingCodeJob = viewScope.launch {
            try {
                val verified = verifyCodeCallback.invoke(currentCode, currentIdentifier)
                if (verified) {
                    onComplete?.invoke(null)
                } else {
                    val message = "Invalid verification code"
                    showStep2Error(message)
                    onComplete?.invoke(message)
                }
            } catch (t: Throwable) {
                val message = t.message ?: "Something went wrong while verifying code"
                showStep2Error(message)
                onComplete?.invoke(message)
            } finally {
                setStep2Loading(isLoading = false)
            }
        }
    }

    private fun setStep1Loading(isLoading: Boolean) {
        sendButton.isEnabled = !isLoading
        step1LoadingOverlay.visibility = if (isLoading) View.VISIBLE else View.GONE
    }

    private fun setStep2Loading(isLoading: Boolean) {
        verifyButton.isEnabled = !isLoading
        step2LoadingOverlay.visibility = if (isLoading) View.VISIBLE else View.GONE
    }

    private fun showStep1Error(message: String) {
        step1ErrorTextView.text = message
        step1ErrorLayout.visibility = View.VISIBLE

        step1ErrorDismissRunnable?.let { uiHandler.removeCallbacks(it) }
        val runnable = Runnable { clearStep1Error() }
        step1ErrorDismissRunnable = runnable
        uiHandler.postDelayed(runnable, ERROR_AUTO_DISMISS_MS)
    }

    private fun clearStep1Error() {
        step1ErrorDismissRunnable?.let { uiHandler.removeCallbacks(it) }
        step1ErrorDismissRunnable = null
        step1ErrorTextView.text = ""
        step1ErrorLayout.visibility = View.GONE
    }

    private fun showStep2Error(message: String) {
        step2ErrorTextView.text = message
        step2ErrorLayout.visibility = View.VISIBLE

        step2ErrorDismissRunnable?.let { uiHandler.removeCallbacks(it) }
        val runnable = Runnable { clearStep2Error() }
        step2ErrorDismissRunnable = runnable
        uiHandler.postDelayed(runnable, ERROR_AUTO_DISMISS_MS)
    }

    private fun clearStep2Error() {
        step2ErrorDismissRunnable?.let { uiHandler.removeCallbacks(it) }
        step2ErrorDismissRunnable = null
        step2ErrorTextView.text = ""
        step2ErrorLayout.visibility = View.GONE
    }

    private fun focusAndShowKeyboard(target: EditText) {
        target.requestFocus()
        target.post {
            val inputManager = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            inputManager?.showSoftInput(target, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    companion object {
        private const val ERROR_AUTO_DISMISS_MS = 3_000L

        fun phoneNumberToE164(phone: String): String {
            val digitsOnly = phone.replace(Regex("[^\\d]"), "")
            if (digitsOnly.length < 10 || digitsOnly.length > 15) {
                throw IllegalArgumentException("Invalid phone number")
            }

            return if (digitsOnly.length == 10) {
                "+1$digitsOnly"
            } else {
                "+$digitsOnly"
            }
        }
    }

    private fun CoroutineScope.isActive(): Boolean {
        return coroutineContext[Job]?.isActive == true
    }
}
