# Web3 Wallet Setup Guide

This guide explains how to configure and enable the Web3 wallet functionality in ProofMode.

## Prerequisites

1. **Privy Account**: Sign up at [privy.io](https://privy.io)
2. **Android Development Environment**: Android Studio with SDK 27+
3. **ProofMode Source Code**: This repository with Web3 integration

## Step 1: Privy Dashboard Setup

1. **Create a Privy App**:
   - Log into your Privy dashboard
   - Create a new app for your ProofMode instance
   - Note down your `APP_ID` and `APP_CLIENT_ID`

2. **Configure Authentication Methods**:
   - Enable SMS authentication in your Privy app settings
   - Enable Email authentication if desired
   - Configure your app's authentication settings

## Step 2: Configure ProofMode

1. **Add Privy Credentials**:
   ```kotlin
   // File: app/src/main/java/org/witness/proofmode/crypto/privy/PrivyConfig.kt
   object PrivyConfig {
       const val APP_ID = "your_privy_app_id_here"
       const val APP_CLIENT_ID = "your_privy_client_id_here"
   }
   ```

2. **Verify Dependencies** (already included):
   ```gradle
   // File: app/build.gradle
   implementation "io.privy:privy-core:0.3.0"
   ```

## Step 3: Build and Test

1. **Build the App**:
   ```bash
   ./gradlew assembleDebug
   ```

2. **Install on Device**:
   ```bash
   adb install app/build/outputs/apk/artwork/debug/ProofMode-*.apk
   ```

3. **Test Authentication**:
   - Open ProofMode app
   - Navigate to Web3 Wallet section
   - Try SMS authentication with your phone number
   - Verify OTP code delivery and verification

## Step 4: Security Considerations

1. **Keep Credentials Secure**:
   - Never commit actual credentials to version control
   - Use the pattern of keeping a `PrivyConfig copy.kt` with real credentials locally
   - Only commit `PrivyConfig.kt` with empty strings

2. **Environment-Specific Configs**:
   - Consider using different Privy apps for development/production
   - Use build variants to manage different configurations

## Troubleshooting

### Common Issues

1. **"Invalid SMS verification code"**:
   - Verify your Privy credentials are correct
   - Check that SMS is enabled in your Privy app settings
   - Ensure phone number is in international format (+1234567890)

2. **"Failed to initialize Privy SDK"**:
   - Check your APP_ID and APP_CLIENT_ID are not empty
   - Verify network connectivity
   - Ensure you're using a valid Privy account

3. **"Failed to send SMS code"**:
   - Verify SMS is enabled in Privy dashboard
   - Check phone number format
   - Ensure you haven't exceeded SMS rate limits

### Debug Logging

Enable verbose logging to see detailed information:
```kotlin
// Already enabled in PrivyManager initialization
logLevel = PrivyLogLevel.VERBOSE
```

Check Android Studio Logcat for `PrivyManager` tags during authentication.

## Support

- **Privy Documentation**: [docs.privy.io](https://docs.privy.io)
- **ProofMode Issues**: Open issues in this GitHub repository
- **Privy Support**: Contact Privy support for SDK-specific issues

## Contributing

When contributing improvements to the Web3 integration:

1. Follow existing code patterns in `PrivyManager.kt`
2. Add appropriate error handling and logging
3. Update this documentation for any new features
4. Test thoroughly with different phone numbers and email addresses
5. Ensure credentials are not committed to version control
