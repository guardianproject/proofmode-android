ProofMode: A CameraV-inspired micro app
========

# Overview
A Simple Utility Building on CameraV and InformaCam technology and architecture, but with a goal of minimalism and simplicity, at the expense of some advanced features.

# Goals 

* Run all of the time in the background without noticeable battery, storage or network impact
* Provide a non-setup, automatic new user experience that works without requiring training
* Use strong cryptography for strong identity and verification features, but not encryption 
* Produce "proof" data formats that can be easily parse, imported by existing tools (CSV)
* Do not modify the original media files; all proof metadata storied in separate file
* Support both full "proof" data generation, as well as more simple sha1/sha256 hash and PGP signature of media files
* Do not require a persistent identity or account generation
