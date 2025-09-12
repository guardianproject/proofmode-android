package org.witness.proofmode

import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Observer
import org.witness.proofmode.crypto.privy.PrivyManager
import org.witness.proofmode.databinding.ActivityWeb3WalletAuthBinding

/**
 * Activity for Web3 wallet authentication using Privy SDK
 * Supports both SMS and email authentication methods
 */
class Web3WalletAuthActivity : AppCompatActivity() {
    
    private lateinit var binding: ActivityWeb3WalletAuthBinding
    private lateinit var privyManager: PrivyManager
    
    private enum class AuthMethod {
        SMS, EMAIL
    }
    
    private var currentAuthMethod = AuthMethod.SMS
    private var showOTPInput = false
    private var currentPhoneNumber = ""
    private var currentEmail = ""
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        binding = ActivityWeb3WalletAuthBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        // Initialize Privy manager
        privyManager = PrivyManager.getInstance(this)
        
        setupToolbar()
        setupUI()
        observePrivyManager()
    }
    
    private fun setupToolbar() {
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.setDisplayShowHomeEnabled(true)
        supportActionBar?.setDisplayShowTitleEnabled(false)
        binding.toolbarTitle.text = "Web3 Wallet"
    }
    
    private fun setupUI() {
        // Setup authentication method toggle
        binding.authMethodGroup.setOnCheckedChangeListener { _, checkedId ->
            currentAuthMethod = when (checkedId) {
                R.id.radioSMS -> AuthMethod.SMS
                R.id.radioEmail -> AuthMethod.EMAIL
                else -> AuthMethod.SMS
            }
            updateInputMethod()
        }
        
        // Setup send code button
        binding.btnSendCode.setOnClickListener {
            when (currentAuthMethod) {
                AuthMethod.SMS -> {
                    currentPhoneNumber = binding.etInput.text.toString().trim()
                    if (currentPhoneNumber.isNotEmpty()) {
                        privyManager.sendSMSCode(currentPhoneNumber)
                    } else {
                        showError("Please enter a valid phone number")
                    }
                }
                AuthMethod.EMAIL -> {
                    currentEmail = binding.etInput.text.toString().trim()
                    if (currentEmail.isNotEmpty()) {
                        privyManager.sendEmailCode(currentEmail)
                    } else {
                        showError("Please enter a valid email address")
                    }
                }
            }
        }
        
        // Setup verify code button
        binding.btnVerifyCode.setOnClickListener {
            val otpCode = binding.etOtpCode.text.toString().trim()
            if (otpCode.isNotEmpty()) {
                when (currentAuthMethod) {
                    AuthMethod.SMS -> privyManager.loginWithSMSCode(otpCode, currentPhoneNumber)
                    AuthMethod.EMAIL -> privyManager.loginWithEmailCode(otpCode, currentEmail)
                }
            } else {
                showError("Please enter the verification code")
            }
        }
        
        // Setup back button for OTP screen
        binding.btnBack.setOnClickListener {
            showOTPInput = false
            updateUI()
        }
        
        // Setup disconnect button
        binding.btnDisconnect.setOnClickListener {
            privyManager.disconnectWallet()
        }
        
        // Initial UI setup
        binding.radioSMS.isChecked = true
        updateInputMethod()
        updateUI()
    }
    
    private fun updateInputMethod() {
        when (currentAuthMethod) {
            AuthMethod.SMS -> {
                binding.etInput.hint = "Enter your phone number"
                binding.etInput.inputType = InputType.TYPE_CLASS_PHONE
            }
            AuthMethod.EMAIL -> {
                binding.etInput.hint = "Enter your email address"
                binding.etInput.inputType = InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
            }
        }
        binding.etInput.text?.clear()
    }
    
    private fun observePrivyManager() {
        privyManager.isConnected.observe(this, Observer { isConnected ->
            if (isConnected) {
                showConnectedState()
            } else {
                showDisconnectedState()
            }
        })
        
        privyManager.walletAddress.observe(this, Observer { address ->
            if (address != null) {
                binding.tvWalletAddress.text = address
            }
        })
        
        privyManager.errorMessage.observe(this, Observer { error ->
            if (error != null) {
                showError(error)
            } else {
                hideError()
            }
        })
        
        privyManager.isLoading.observe(this, Observer { isLoading ->
            binding.progressBar.visibility = if (isLoading) View.VISIBLE else View.GONE
            binding.btnSendCode.isEnabled = !isLoading
            binding.btnVerifyCode.isEnabled = !isLoading
        })
        
        // Check for successful code sending to show OTP input
        privyManager.errorMessage.observe(this, Observer { error ->
            if (error == null && !showOTPInput && binding.etInput.text?.isNotEmpty() == true) {
                // Code was sent successfully, show OTP input
                showOTPInput = true
                updateUI()
            }
        })
    }
    
    private fun updateUI() {
        if (privyManager.isConnected.value == true) {
            showConnectedState()
        } else if (showOTPInput) {
            showOTPInputState()
        } else {
            showAuthMethodSelectionState()
        }
    }
    
    private fun showConnectedState() {
        binding.layoutConnected.visibility = View.VISIBLE
        binding.layoutDisconnected.visibility = View.GONE
        binding.layoutOtpInput.visibility = View.GONE
        
        val address = privyManager.getCurrentWalletAddress()
        binding.tvWalletAddress.text = address ?: "No address available"
    }
    
    private fun showDisconnectedState() {
        binding.layoutConnected.visibility = View.GONE
        binding.layoutDisconnected.visibility = View.VISIBLE
        binding.layoutOtpInput.visibility = View.GONE
        showOTPInput = false
        updateUI()
    }
    
    private fun showAuthMethodSelectionState() {
        binding.layoutConnected.visibility = View.GONE
        binding.layoutDisconnected.visibility = View.VISIBLE
        binding.layoutOtpInput.visibility = View.GONE
        
        binding.layoutAuthMethod.visibility = View.VISIBLE
        binding.layoutInput.visibility = View.VISIBLE
        binding.btnSendCode.visibility = View.VISIBLE
    }
    
    private fun showOTPInputState() {
        binding.layoutConnected.visibility = View.GONE
        binding.layoutDisconnected.visibility = View.VISIBLE
        binding.layoutOtpInput.visibility = View.VISIBLE
        
        binding.layoutAuthMethod.visibility = View.GONE
        binding.layoutInput.visibility = View.GONE
        binding.btnSendCode.visibility = View.GONE
        
        val methodText = if (currentAuthMethod == AuthMethod.SMS) "SMS" else "email"
        val contactInfo = if (currentAuthMethod == AuthMethod.SMS) currentPhoneNumber else currentEmail
        binding.tvOtpInstruction.text = "Enter the verification code sent via $methodText to $contactInfo"
    }
    
    private fun showError(message: String) {
        binding.tvError.text = message
        binding.tvError.visibility = View.VISIBLE
    }
    
    private fun hideError() {
        binding.tvError.visibility = View.GONE
    }
    
    override fun onSupportNavigateUp(): Boolean {
        onBackPressed()
        return true
    }
}
