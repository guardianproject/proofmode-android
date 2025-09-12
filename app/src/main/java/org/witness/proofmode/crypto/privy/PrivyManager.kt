package org.witness.proofmode.crypto.privy

import android.content.Context
import android.content.SharedPreferences
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.preference.PreferenceManager
import io.privy.sdk.Privy
import io.privy.sdk.PrivyConfig as PrivySdkConfig
import io.privy.logging.PrivyLogLevel
import io.privy.auth.AuthState
import io.privy.auth.PrivyUser
import org.witness.proofmode.crypto.privy.PrivyConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collect

/**
 * Manages Privy Web3 wallet authentication and connection
 */
class PrivyManager private constructor(private val context: Context) {
    
    companion object {
        @Volatile
        private var INSTANCE: PrivyManager? = null
        
        fun getInstance(context: Context): PrivyManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: PrivyManager(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
    
    private var privy: Privy? = null
    private val coroutineScope = CoroutineScope(Dispatchers.Main)
    
    // LiveData for observing authentication state
    private val _isConnected = MutableLiveData<Boolean>(false)
    val isConnected: LiveData<Boolean> = _isConnected
    
    private val _walletAddress = MutableLiveData<String?>()
    val walletAddress: LiveData<String?> = _walletAddress
    
    private val _errorMessage = MutableLiveData<String?>()
    val errorMessage: LiveData<String?> = _errorMessage
    
    private val _isLoading = MutableLiveData<Boolean>(false)
    val isLoading: LiveData<Boolean> = _isLoading
    
    // Authentication verification state
    private val _pendingVerification = MutableLiveData<String?>(null) // "sms" or "email"
    val pendingVerification: LiveData<String?> = _pendingVerification
    
    private val sharedPreferences: SharedPreferences by lazy {
        PreferenceManager.getDefaultSharedPreferences(context)
    }
    
    init {
        initializePrivy()
        checkSavedAuthenticationState()
    }
    
    /**
     * Initialize Privy SDK with app configuration
     */
    private fun initializePrivy() {
        coroutineScope.launch {
            try {
                val config = PrivySdkConfig(
                    appId = PrivyConfig.APP_ID,
                    appClientId = PrivyConfig.APP_CLIENT_ID,
                    logLevel = PrivyLogLevel.VERBOSE,
                    customAuthConfig = null
                )
                
                privy = Privy.init(context, config)
                checkAuthenticationStatus()
            } catch (e: Exception) {
                _errorMessage.value = "Failed to initialize Privy SDK: ${e.message}"
            }
        }
    }
    
    /**
     * Check if user has saved authentication data
     */
    private fun checkSavedAuthenticationState() {
        val savedWalletAddress = sharedPreferences.getString("privy_wallet_address", null)
        val isAuthenticated = sharedPreferences.getBoolean("privy_is_authenticated", false)
        
        if (isAuthenticated && !savedWalletAddress.isNullOrEmpty()) {
            _isConnected.value = true
            _walletAddress.value = savedWalletAddress
        }
    }
    
    /**
     * Check current authentication status with Privy
     */
    private fun checkAuthenticationStatus() {
        coroutineScope.launch {
            try {
                privy?.let { privyInstance ->
                    // Use the StateFlow to observe auth state changes
                    privyInstance.authState.collect { authState ->
                        when (authState) {
                            is io.privy.auth.AuthState.Authenticated -> {
                                _isConnected.value = true
                                val user = authState.user
                                ensureUserHasWallet(user)
                            }
                            is io.privy.auth.AuthState.Unauthenticated -> {
                                _isConnected.value = false
                                _walletAddress.value = null
                            }
                            is io.privy.auth.AuthState.AuthenticatedUnverified -> {
                                _isConnected.value = true
                                _errorMessage.value = "Verifying authentication..."
                            }
                            is io.privy.auth.AuthState.NotReady -> {
                                // SDK still initializing
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                _errorMessage.value = "Failed to check authentication status: ${e.message}"
            }
        }
    }
    
    /**
     * Send SMS verification code to phone number
     */
    fun sendSMSCode(phoneNumber: String) {
        _isLoading.value = true
        _errorMessage.value = null
        
        coroutineScope.launch {
            try {
                privy?.let { privyInstance ->
                    // Use the actual Privy SMS API
                    privyInstance.sms.sendCode(phoneNumber)
                    // OTP was successfully sent
                    _pendingVerification.value = "sms"
                    saveAuthData("sms", phoneNumber)
                }
            } catch (e: Exception) {
                _errorMessage.value = "Failed to send SMS code: ${e.message}"
                _pendingVerification.value = null
            } finally {
                _isLoading.value = false
            }
        }
    }
    
    /**
     * Send email verification code to email address
     */
    fun sendEmailCode(email: String) {
        _isLoading.value = true
        _errorMessage.value = null
        
        coroutineScope.launch {
            try {
                privy?.let { privyInstance ->
                    // Use the actual Privy Email API
                    privyInstance.email.sendCode(email)
                    // OTP was successfully sent
                    _pendingVerification.value = "email"
                    saveAuthData("email", email)
                }
            } catch (e: Exception) {
                _errorMessage.value = "Failed to send email code: ${e.message}"
                _pendingVerification.value = null
            } finally {
                _isLoading.value = false
            }
        }
    }
    
    /**
     * Verify SMS code and complete authentication
     */
    fun loginWithSMSCode(code: String, phoneNumber: String) {
        _isLoading.value = true
        _errorMessage.value = null
        
        coroutineScope.launch {
            try {
                privy?.let { privyInstance ->
                    // Trim whitespace from code to avoid formatting issues
                    val trimmedCode = code.trim()
                    
                    // Use the actual Privy SMS login API with Result handling for 0.3.0
                    val result = privyInstance.sms.loginWithCode(trimmedCode, phoneNumber)
                    result.fold(
                        onSuccess = { user ->
                            // User logged in successfully
                            _isConnected.value = true
                            _pendingVerification.value = null
                            // Auth state will be updated via StateFlow observer
                            // Ensure user has a wallet
                            ensureUserHasWallet(user)
                        },
                        onFailure = { error ->
                            _errorMessage.value = "Failed to verify SMS code: ${error.message}"
                            _pendingVerification.value = null
                        }
                    )
                }
            } catch (e: Exception) {
                _errorMessage.value = "Failed to verify SMS code: ${e.message}"
                _pendingVerification.value = null
            } finally {
                _isLoading.value = false
            }
        }
    }
    
    /**
     * Verify email code and complete authentication
     */
    fun loginWithEmailCode(code: String, email: String) {
        _isLoading.value = true
        _errorMessage.value = null
        
        coroutineScope.launch {
            try {
                privy?.let { privyInstance ->
                    // Trim whitespace from code to avoid formatting issues
                    val trimmedCode = code.trim()
                    
                    // Use the actual Privy Email login API with Result handling for 0.3.0
                    val result = privyInstance.email.loginWithCode(trimmedCode, email)
                    result.fold(
                        onSuccess = { user ->
                            // User logged in successfully
                            _isConnected.value = true
                            _pendingVerification.value = null
                            // Auth state will be updated via StateFlow observer
                            // Ensure user has a wallet
                            ensureUserHasWallet(user)
                        },
                        onFailure = { error ->
                            _errorMessage.value = "Failed to verify email code: ${error.message}"
                            _pendingVerification.value = null
                        }
                    )
                }
            } catch (e: Exception) {
                _errorMessage.value = "Failed to verify email code: ${e.message}"
                _pendingVerification.value = null
            } finally {
                _isLoading.value = false
            }
        }
    }
    
    /**
     * Logout user and clear authentication state
     */
    fun logout() {
        coroutineScope.launch {
            try {
                privy?.let { privyInstance ->
                    // Use the actual Privy logout API
                    privyInstance.logout()
                }
                
                // Clear local state
                clearAuthData()
                _isConnected.value = false
                _walletAddress.value = null
                _pendingVerification.value = null
                _errorMessage.value = null
                
            } catch (e: Exception) {
                _errorMessage.value = "Failed to logout: ${e.message}"
            }
        }
    }
    
    /**
     * Ensure authenticated user has an embedded wallet
     */
    private fun ensureUserHasWallet(user: PrivyUser) {
        coroutineScope.launch {
            try {
                // Check if user already has an embedded Ethereum wallet
                val existingWallets = user.embeddedEthereumWallets
                
                if (existingWallets.isNotEmpty()) {
                    // User already has a wallet, use the first one
                    val wallet = existingWallets.first()
                    _walletAddress.value = wallet.address
                    saveWalletData(wallet.address)
                } else {
                    // User doesn't have a wallet yet, create one
                    val result = user.createEthereumWallet(allowAdditional = false)
                    result.fold(
                        onSuccess = { ethereumWallet ->
                            _walletAddress.value = ethereumWallet.address
                            saveWalletData(ethereumWallet.address)
                        },
                        onFailure = { error ->
                            _errorMessage.value = "Failed to create wallet: ${error.message}"
                        }
                    )
                }
            } catch (e: Exception) {
                _errorMessage.value = "Failed to manage wallet: ${e.message}"
            }
        }
    }
    
    /**
     * Get current wallet address
     */
    fun getCurrentWalletAddress(): String? {
        return _walletAddress.value ?: run {
            val savedAddress = sharedPreferences.getString("privy_wallet_address", null)
            if (!savedAddress.isNullOrEmpty()) {
                _walletAddress.value = savedAddress
                savedAddress
            } else null
        }
    }
    
    /**
     * Check if user is currently authenticated
     */
    fun isAuthenticated(): Boolean {
        return _isConnected.value ?: false
    }
    
    /**
     * Disconnect wallet and logout user
     * Alias for logout() method for compatibility
     */
    fun disconnectWallet() {
        logout()
    }
    
    // Helper methods for data persistence
    private fun saveAuthData(method: String, identifier: String) {
        sharedPreferences.edit()
            .putString("privy_auth_method", method)
            .putString("privy_auth_identifier", identifier)
            .putBoolean("privy_is_authenticated", true)
            .apply()
    }
    
    private fun saveWalletData(address: String) {
        sharedPreferences.edit()
            .putString("privy_wallet_address", address)
            .apply()
    }
    
    private fun clearAuthData() {
        sharedPreferences.edit()
            .remove("privy_auth_method")
            .remove("privy_auth_identifier")
            .remove("privy_wallet_address")
            .putBoolean("privy_is_authenticated", false)
            .apply()
    }
}
