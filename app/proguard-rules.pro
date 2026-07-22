# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.

# Keep native library loader
-keep class com.baize.ai.** { *; }
-dontwarn com.baize.ai.**

