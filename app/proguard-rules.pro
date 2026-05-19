# Add project specific ProGuard rules here.
# By default, the flags in this file are appended to flags specified
# in /home/n8fr8/dev/android/sdk/tools/proguard/proguard-android.txt
# You can edit the include path and order by changing the proguardFiles
# directive in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# Add any project specific keep options here:

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

-optimizationpasses 5
-dontusemixedcaseclassnames
-dontskipnonpubliclibraryclasses
-dontskipnonpubliclibraryclassmembers
-dontpreverify
-verbose
-repackageclasses ''
-allowaccessmodification
-keepattributes *Annotation*

##-injars libs

-outjars bin/classes-processed.jar

-dontwarn javax.naming.**
-dontwarn android.support.**
-dontwarn okio.**
-dontwarn javax.annotation.Nullable
-dontwarn javax.annotation.ParametersAreNonnullByDefault

# Bitcoinj and its optional dependencies
-dontwarn org.fusesource.leveldbjni.**
-dontwarn org.iq80.leveldb.**
-dontwarn org.slf4j.impl.**

# Missing classes referenced from bitcoinj on Android
-dontwarn sun.misc.Cleaner
-dontwarn sun.nio.ch.DirectBuffer

# bitcoinj uses full protobuf-java but Android uses protobuf-javalite;
# suppress warnings for full protobuf classes not present in lite
-dontwarn com.google.protobuf.AbstractMessageLite$Builder$LimitedInputStream
-dontwarn com.google.protobuf.Descriptors$Descriptor
-dontwarn com.google.protobuf.Descriptors$EnumDescriptor
-dontwarn com.google.protobuf.Descriptors$EnumValueDescriptor
-dontwarn com.google.protobuf.Descriptors$FieldDescriptor
-dontwarn com.google.protobuf.Descriptors$FileDescriptor
-dontwarn com.google.protobuf.Descriptors$FileDescriptor$InternalDescriptorAssigner
-dontwarn com.google.protobuf.Descriptors$OneofDescriptor
-dontwarn com.google.protobuf.Extension
-dontwarn com.google.protobuf.GeneratedMessage
-dontwarn com.google.protobuf.GeneratedMessage$Builder
-dontwarn com.google.protobuf.GeneratedMessage$BuilderParent
-dontwarn com.google.protobuf.GeneratedMessage$FieldAccessorTable
-dontwarn com.google.protobuf.GeneratedMessage$GeneratedExtension
-dontwarn com.google.protobuf.Message
-dontwarn com.google.protobuf.MessageOrBuilder
-dontwarn com.google.protobuf.RepeatedFieldBuilder
-dontwarn com.google.protobuf.SingleFieldBuilder
-dontwarn com.google.protobuf.TextFormat
-dontwarn com.google.protobuf.UnknownFieldSet
-dontwarn com.google.protobuf.UnknownFieldSet$Builder
-dontwarn com.google.protobuf.AbstractMessage$Builder
-dontwarn com.google.protobuf.ExtensionRegistry
-dontwarn com.google.protobuf.Message$Builder
-dontwarn com.google.protobuf.ProtocolMessageEnum

####
# Crypto / signing / framework subpackages that ARE looked up by name
# (JNI from c2pa-rs, internal class-name lookups in BouncyCastle, etc.) —
# these must keep their class names.
-keep class org.spongycastle.** { *; }
# org.bouncycastle.** and org.contentauth.c2pa.** are kept by the
# c2pa-android library's consumer-rules.pro; do not duplicate here.

# ProofMode subpackages that are referenced reflectively, declared in the
# manifest, exposed as a library API, or otherwise need stable names.
# proofsign is deliberately excluded — see the obfuscation block below.
-keep class org.witness.proofmode.* { *; }
-keep class org.witness.proofmode.c2pa.* { *; }
-keep class org.witness.proofmode.c2pa.custom.** { *; }
-keep class org.witness.proofmode.c2pa.selfsign.** { *; }
-keep class org.witness.proofmode.camera.** { *; }
-keep class org.witness.proofmode.crypto.** { *; }
-keep class org.witness.proofmode.notaries.** { *; }
-keep class org.witness.proofmode.notarization.** { *; }
-keep class org.witness.proofmode.onboarding.** { *; }
-keep class org.witness.proofmode.service.** { *; }
-keep class org.witness.proofmode.share.** { *; }
-keep class org.witness.proofmode.storage.** { *; }
-keep class org.witness.proofmode.ui.** { *; }
-keep class org.witness.proofmode.util.** { *; }

-keep public class * extends android.app.Activity
-keep public class * extends android.app.Application
-keep public class * extends android.app.Service
-keep public class * extends android.content.BroadcastReceiver
-keep public class * extends android.content.ContentProvider
-keep public class * extends android.app.backup.BackupAgentHelper
-keep public class * extends android.preference.Preference
-keep public class com.android.vending.licensing.ILicensingService

-keepclasseswithmembernames class * {
    native <methods>;
}

-keepclasseswithmembers class * {
    public <init>(android.content.Context, android.util.AttributeSet);
}

-keepclasseswithmembers class * {
    public <init>(android.content.Context, android.util.AttributeSet, int);
}

-keepclassmembers class * extends android.app.Activity {
   public void *(android.view.View);
}

-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

-keep class * implements android.os.Parcelable {
  public static final android.os.Parcelable$Creator *;
}

# ----------------------------------------------------------------------
# proofsign obfuscation — the Frida agent's published attack hardcodes
# class names like ProofSignClient, signC2PAClaimWithDeviceAuth,
# Result$Success, AttestationMode, CaptureAuthority. Renaming these in
# release builds breaks the Java.use() lookups the agent depends on.
# ----------------------------------------------------------------------

# Allow the proofsign package to be renamed but keep its members reachable.
# This sub-package is intentionally NOT covered by the broad keeps above.
-keeppackagenames !org.witness.proofmode.c2pa.proofsign.**
-repackageclasses 'pm'
-keep,allowobfuscation class org.witness.proofmode.c2pa.proofsign.** { *; }

# kotlinx.serialization needs the generated $$serializer and Companion
# accessors to be reachable for the @Serializable SignerConfiguration in
# ProofSignC2PASigner. These rules are obfuscation-safe (they keep
# *members* by signature, not the class name).
-keep,includedescriptorclasses class org.witness.proofmode.c2pa.proofsign.**$$serializer { *; }
-keepclassmembers class org.witness.proofmode.c2pa.proofsign.** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}
