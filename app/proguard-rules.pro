# OmniReader ProGuard Rules
-keep class com.omnireader.data.model.** { *; }
-keepclassmembers class * extends androidx.lifecycle.ViewModel { <init>(...); }
