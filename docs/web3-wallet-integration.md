# Web3 Wallet Integration - Technical Documentation

This document provides technical details about the Web3 wallet integration added to ProofMode using the Privy SDK.

## Architecture Overview

The Web3 wallet functionality is built around three core components:

### 1. PrivyManager.kt
The central singleton class that manages all Privy SDK interactions:

- **Authentication Flow**: Handles SMS/Email OTP sending and verification
- **Wallet Management**: Creates and manages embedded Ethereum wallets
- **State Management**: Uses LiveData for reactive UI updates
- **Error Handling**: Comprehensive error catching and user-friendly messaging

### 2. PrivyConfig.kt
Configuration object containing Privy SDK credentials:

```kotlin
object PrivyConfig {
    const val APP_ID = "your_privy_app_id"
    const val APP_CLIENT_ID = "your_privy_client_id"
}
```

### 3. Web3WalletAuthActivity.kt
User interface for the authentication flow:

- **Phone/Email Input**: Collection of authentication credentials
- **OTP Verification**: Input and verification of one-time passwords
- **Wallet Display**: Shows wallet connection status and addresses

## Key Features

### Authentication Methods
- **SMS OTP**: Phone number verification with SMS codes
- **Email OTP**: Email verification with emailed codes
- **Code Trimming**: Automatic whitespace removal from OTP inputs

### Wallet Management
- **Automatic Creation**: Embedded Ethereum wallets created upon successful authentication
- **Address Storage**: Wallet addresses saved in secure SharedPreferences
- **State Persistence**: Authentication state maintained across app sessions

### Error Handling
- **Network Errors**: Graceful handling of connectivity issues
- **Invalid Codes**: Clear messaging for incorrect OTP entries
- **SDK Failures**: Fallback behavior for Privy SDK errors

## Implementation Notes

### Dependencies
```gradle
implementation "io.privy:privy-core:0.3.0"
```

### SDK Initialization
```kotlin
val config = PrivySdkConfig(
    appId = PrivyConfig.APP_ID,
    appClientId = PrivyConfig.APP_CLIENT_ID,
    logLevel = PrivyLogLevel.VERBOSE,
    customAuthConfig = null
)
privy = Privy.init(context, config)
```

### Authentication State Monitoring
```kotlin
privyInstance.authState.collect { authState ->
    when (authState) {
        is AuthState.Authenticated -> handleAuthenticated(authState.user)
        is AuthState.Unauthenticated -> handleUnauthenticated()
        is AuthState.AuthenticatedUnverified -> handleUnverified()
        is AuthState.NotReady -> handleNotReady()
    }
}
```

## Security Considerations

1. **Credential Storage**: Privy SDK handles secure storage of cryptographic keys
2. **Session Management**: Authentication sessions managed by Privy infrastructure
3. **Local Storage**: Only wallet addresses and basic state stored locally
4. **Network Security**: All communications encrypted via HTTPS

## Debugging

Enable verbose logging in `PrivyConfig` for debugging authentication issues:
```kotlin
logLevel = PrivyLogLevel.VERBOSE
```

Common debugging steps:
1. Verify APP_ID and APP_CLIENT_ID are correctly configured
2. Check network connectivity for OTP delivery
3. Ensure phone numbers are in proper international format
4. Verify OTP codes are entered without extra spaces

## Future Enhancements

Potential areas for expansion:
- Multi-chain wallet support (Solana, Polygon, etc.)
- Custom wallet naming and management
- Integration with ProofMode's existing cryptographic signatures
- Wallet-based media file signing and verification
