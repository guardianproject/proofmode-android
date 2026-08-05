# Add project specific ProofMode Wallet Infra rules here.

# ---------------------------------------------------------------------------
# ZeroDev account-abstraction SDK (app.zerodev:zerodev-aa)
#
# The AAR ships NO consumer-rules.pro of its own, so nothing keeps it in the
# app's R8 run. libzerodev_aa.so drives the custom signer entirely by name:
# NativeLib.nSignerCustom(Object, long[]) hands our SignerImpl instance to
# native code as a bare jobject, which then does GetObjectClass + GetMethodID
# for each callback. The name strings are literally in the .so:
#
#     signHash                       ([B)[B
#     signMessage                    ([B)[B
#     signTypedDataHash              ([B)[B
#     getAddress                     ()[B
#     getProvidesSignAuthorization   ()Z
#     signAuthorization              (J[BJ)Ldev/zerodev/aa/Authorization;
#
# Note the last descriptor: the native side resolves dev.zerodev.aa.Authorization
# by name too (as it does dev.zerodev.aa.AaException), so the SDK's class names
# must survive as well as its members. The jar is ~54 KB — keep all of it rather
# than tracking which half is JNI-visible.
-keep class dev.zerodev.aa.** { *; }

# Our SignerImpl implementation (PrivyZeroDevBridge) is looked up off the live
# object, so its *class* may be renamed, but the six callback methods above must
# keep their names. Keeping the interface above already pins the names across
# the hierarchy; this is the explicit, self-documenting guarantee. It also covers
# the Kotlin-generated forwarders for the interface's default methods
# (getProvidesSignAuthorization / signAuthorization -> SignerImpl$DefaultImpls).
-keepclassmembers class * implements dev.zerodev.aa.SignerImpl {
    public <methods>;
}
