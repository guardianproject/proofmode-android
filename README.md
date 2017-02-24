ProofMode: A CameraV-inspired micro app
========

# Overview

ProofMode is light, minimal "reboot" of our full encrypted, verified secure camera app, CameraV (https://guardianproject.info/apps/camerav). Our hope was to create a lightweight, almost invisible utility, that you can run all of the time on your phone, that automatically extra digital proof data to all photos and videos you take. This data can then be easily shared through a "Share Proof" share action, to anyone you choose.

While we are very proud of the work we did with the CameraV and InformaCam projects, the end results was a complex application and proprietary data format that required a great deal of investment by any user or community that wished to adopt it. Furthermore, it was an app that you had to decide and remember to use, in a moment of crisis. With ProofMode, we both wanted to simplify the adoption of the tool, and make it nearly invisible to the end-user, while making it the adoption of the tool by organizations painless through simple formats like CSV and known formats like PGP signatures.

# Design Goals 

* Run all of the time in the background without noticeable battery, storage or network impact
* Provide a no-setup-required, automatic new user experience that works without requiring training
* Use strong cryptography for strong identity and verification features, but not encryption 
* Produce "proof" sensor data formats that can be easily parse, imported by existing tools (CSV)
* Do not modify the original media files; all proof metadata storied in separate file
* Support chain of custody needs through automatic creation of sha256 hashes and PGP signatures
* Do not require a persistent identity or account generation

# Screenshots

![Screenshot](https://raw.githubusercontent.com/guardianproject/proofmode/master/art/screens/Screenshot_20170222-173854.jpg)
![Screenshot](https://raw.githubusercontent.com/guardianproject/proofmode/master/art/screens/Screenshot_20170222-174004.jpg)
![Screenshot](https://raw.githubusercontent.com/guardianproject/proofmode/master/art/screens/Screenshot_20170222-174126.jpg)

# Contributions

* Some icons were used under the APL 2.0 license from the Google Material Design Icon library: https://material.io/icons/
* The App Intro library is used under the APL 2.0 license: https://github.com/paolorotolo/AppIntro
* Spongy Castle uses the same adaptation of the MIT X11 License as Bouncy Castle.: https://rtyley.github.io/spongycastle/

