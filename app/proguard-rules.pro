# EchoNode ProGuard Rules

# Keep Nearby Connections callbacks
-keep class com.google.android.gms.nearby.** { *; }

# Keep Kotlin coroutines
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}

# Keep data classes
-keep class com.echoshield.echonode.data.** { *; }
-keep class com.echoshield.echonode.viewmodel.** { *; }
