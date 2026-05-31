# rust-nostr uniffi bindings are loaded reflectively via JNA; keep them intact.
-keep class rust.nostr.** { *; }
-keep class com.sun.jna.** { *; }
-keep class * implements com.sun.jna.** { *; }
-dontwarn java.awt.**
