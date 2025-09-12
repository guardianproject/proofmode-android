# Overview

ProofMode is light, minimal "reboot" of our full encrypted, verified secure camera app, CameraV (https://guardianproject.info/apps/camerav). Our hope was to create a lightweight, almost invisible utility, that you can run all of the time on your phone, that automatically extra digital proof data to all photos and videos you take. This data can then be easily shared through a "Share Proof" share action, to anyone you choose.

While we are very proud of the work we did with the CameraV and InformaCam projects, the end results was a complex application and proprietary data format that required a great deal of investment by any user or community that wished to adopt it. Furthermore, it was an app that you had to decide and remember to use, in a moment of crisis. With ProofMode, we both wanted to simplify the adoption of the tool, and make it nearly invisible to the end-user, while making it the adoption of the tool by organizations painless through simple formats like CSV and known formats like PGP signatures.

# ✨ NEW: Web3 Wallet Integration

This version of ProofMode includes Web3 wallet authentication through Privy SDK integration, enabling users to securely authenticate and manage cryptographic wallets alongside their proof data.

## Features

- **SMS/Email OTP Authentication**: Secure phone number and email verification using Privy's authentication system
- **Embedded Wallet Creation**: Automatic Ethereum wallet generation for authenticated users
- **Cross-Platform Ready**: Implementation designed to match iOS patterns for future consistency
- **Secure Key Management**: Wallet addresses and authentication state managed through encrypted storage

## Setup

To enable Web3 wallet functionality:

1. **Configure Privy Credentials**: 
   - Open `app/src/main/java/org/witness/proofmode/crypto/privy/PrivyConfig.kt`
   - Add your Privy `APP_ID` and `APP_CLIENT_ID` from your Privy dashboard
   
2. **Dependencies**: 
   - The required Privy SDK dependency is already included in `app/build.gradle`
   - No additional setup required

## Usage

Users can access Web3 wallet features through the new "Web3 Wallet" option in the app, allowing them to:
- Authenticate using phone number or email with OTP verification
- Generate and manage embedded Ethereum wallets
- View wallet addresses and connection status
- Securely logout and disconnect wallets

## Implementation Details

- **PrivyManager.kt**: Core wallet management and authentication logic
- **PrivyConfig.kt**: Configuration for Privy SDK credentials  
- **Web3WalletAuthActivity.kt**: User interface for authentication flow
- Full error handling and logging for debugging authentication issues

For detailed documentation:
- **[Web3 Wallet Setup Guide](docs/web3-wallet-setup.md)**: Step-by-step configuration and troubleshooting
- **[Technical Documentation](docs/web3-wallet-integration.md)**: Architecture details and implementation notes

This integration enhances ProofMode's cryptographic capabilities while maintaining the app's core principle of invisible, automatic operation.

# Design Goals 

* Run all of the time in the background without noticeable battery, storage or network impact
* Provide a no-setup-required, automatic new user experience that works without requiring training
* Use strong cryptography for strong identity and verification features, but not encryption 
* Produce "proof" sensor data formats that can be easily parse, imported by existing tools (CSV)
* Do not modify the original media files; all proof metadata storied in separate file
* Support chain of custody needs through automatic creation of sha256 hashes and PGP signatures
* Do not require a persistent identity or account generation

# What It Does

1. User installs ProofMode app
2. ProofMode app automatically generates a private/public OpenPGP keypair as a persistent "proof" identity within the app
3. When the user takes a photo or video, ProofMode wakes up, signs the new media file with their private key
	* Additionally, a sensor data snapshot is taken to gather correlating proof. This is saved as a CSV file, and also signed with the OpenPGP key.
4. If the user wants to share a specific photo or video as "proof", they can just select the "Share Proof" option from the Android global share menu from the Gallery or Photos app.
	* This will then re-share the media file, plus all the related proof files and digital signatures, to the app of the users choice, along with a summary of when the file was created, what the public key identity is, and so on.
 	* The user can also select multiple photos and videos to "Share Proof" for, and create a batch of data, with correlating sensor data for all media files, combined together in a single log of an "event"
5. The user can also choose to publish their public key on pgp.mit.edu directory from within the app menu, or directly share their public key with anyone who would need it for verifying digital signatures. 
6. As the receiver of a ProofMode data set, you can verify the media file was not tampered with, verify the public key idenity of who signed the files, to see if it is consistent with what you expect, and examine all the correlating sensor data around the event, for extra context and evidence.
	* The CSV data can easily be imported into any spreadsheet or visualization type tool to create maps, charts, graphs, and other means of understanding the values included, over time, especially across multiple proof points of media

# Screenshots

![Screenshot](https://raw.githubusercontent.com/guardianproject/proofmode/master/art/screens/Screenshot_20170222-173854.jpg)
![Screenshot](https://raw.githubusercontent.com/guardianproject/proofmode/master/art/screens/Screenshot_20170222-174004.jpg)
![Screenshot](https://raw.githubusercontent.com/guardianproject/proofmode/master/art/screens/Screenshot_20170222-174126.jpg)

# Contributions

* Some icons were used under the APL 2.0 license from the Google Material Design Icon library: https://material.io/icons/
* The App Intro library is used under the APL 2.0 license: https://github.com/paolorotolo/AppIntro
* Spongy Castle uses the same adaptation of the MIT X11 License as Bouncy Castle.: https://rtyley.github.io/spongycastle/

