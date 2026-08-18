# Lumos - android-app/app / R8 rules for the empty shell.
# Author: dhar.abhishek.email@gmail.com
# Implements: TRS-REP-001
# ADRs: -
# Threading: n/a
# Notes: Empty by design. The shell has no code to keep; real rules arrive with
# the modules that need them, and a speculative -keep now would silently disable
# shrinking for classes nobody has written.

# ---- JNA and Lazysodium (WP-02 S4b) ---------------------------------------
#
# JNA binds native functions by REFLECTION over class and method names, so R8
# renaming or removing them breaks the binding at runtime rather than at build
# time - the failure arrives as an UnsatisfiedLinkError in a release build that
# every debug build survived. These keeps are the minimum that leaves the
# binding intact; everything else in the app still shrinks.
-keep class com.sun.jna.** { *; }
-keep class * implements com.sun.jna.Library { *; }
-keep class * extends com.sun.jna.Structure { *; }
# Sodium and SodiumAndroid are the direct-mapping targets of Native.register.
-keep class com.goterl.lazysodium.Sodium { *; }
-keep class com.goterl.lazysodium.SodiumAndroid { *; }
-keep class com.goterl.lazysodium.LazySodium { *; }
-keep class com.goterl.lazysodium.LazySodiumAndroid { *; }
#
# JNA's sources reference desktop-only APIs that no Android runtime has. They
# are unreachable here; the warnings are about R8 not finding classes that were
# never going to be called.
-dontwarn java.awt.**
-dontwarn com.sun.jna.internal.**
