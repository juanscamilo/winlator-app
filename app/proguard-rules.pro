# Add project specific ProGuard rules here.
# By default, the flags in this file are appended to flags specified
# in C:\tools\adt-bundle-windows-x86_64-20131030\sdk/tools/proguard/proguard-android.txt
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

-dontobfuscate
# zstd-jni: its native code reads/writes Java fields (srcPos, dstPos, ...) and calls
# methods via JNI. Without this, R8 strips them and decompressing the rootfs
# (first launch, right after granting storage permission) crashes.
-keep class com.github.luben.zstd.** { *; }
-dontwarn com.github.luben.zstd.**

# Members called from Winlator's own native code via GetMethodID.
-keepclassmembers class com.winlator.** {
    void clearWindowContent(int);
    void flushFrontbuffer(int, int);
    long getGLXContextPtr(int, int);
    long getWindowHardwareBuffer(int, boolean);
    int getWindowHeight(int);
    short[] getWindowSize(int);
    int getWindowWidth(int);
    *** handleConnectionShutdown(...);
    *** handleExistingConnection(...);
    *** handleNewConnection(...);
    void killAllConnections();
    void killConnection(int);
    void setNativeHandle(int);
    void setStride(short);
    *** updateWindowContent(...);
}

# Keep every class that declares native methods intact (names + members).
-keepclasseswithmembers,includedescriptorclasses class * {
    native <methods>;
}
