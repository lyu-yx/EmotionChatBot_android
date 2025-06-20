# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# Uncomment this to preserve the line number information for
# debugging stack traces.
#-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile

# Keep Alibaba DashScope SDK classes
-keep class com.alibaba.dashscope.** { *; }
-dontwarn com.alibaba.dashscope.**

# Keep RxJava classes
-keep class io.reactivex.rxjava2.** { *; }
-dontwarn io.reactivex.rxjava2.**

# Keep Aliyun ASR related classes
-keep class com.skythinker.gptassistant.AliyunAsrClient { *; }