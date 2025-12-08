# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.kts.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# Keep data classes for serialization
-keep class com.imagedit.app.domain.model.** { *; }
-keepclassmembers class com.imagedit.app.domain.model.** { *; }

# Keep Hilt generated classes
-keep class dagger.hilt.** { *; }
-keep class javax.inject.** { *; }
-keep class * extends dagger.hilt.android.internal.managers.ViewComponentManager$FragmentContextWrapper { *; }

# Keep Room database classes
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class *
-dontwarn androidx.room.paging.**

# Keep Kotlin serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# Keep Compose
-keep class androidx.compose.** { *; }
-dontwarn androidx.compose.**

# ===== PERFORMANCE OPTIMIZATION =====

# Compose Runtime Optimization - Prevents lock verification failures
-keep class androidx.compose.runtime.** { *; }
-keep class androidx.compose.runtime.snapshots.** { *; }
-dontwarn androidx.compose.runtime.**

# CRITICAL: Keep SnapshotStateList methods to prevent lock verification failures
# These methods were causing "failed lock verification and will run slower" warnings
-keepclassmembers class androidx.compose.runtime.snapshots.SnapshotStateList {
    boolean conditionalUpdate(boolean, kotlin.jvm.functions.Function1);
    boolean conditionalUpdate$default(androidx.compose.runtime.snapshots.SnapshotStateList, boolean, kotlin.jvm.functions.Function1, int, java.lang.Object);
    java.lang.Object mutate(kotlin.jvm.functions.Function1);
    void update(boolean, kotlin.jvm.functions.Function1);
    void update$default(androidx.compose.runtime.snapshots.SnapshotStateList, boolean, kotlin.jvm.functions.Function1, int, java.lang.Object);
    *;
}

# Prevent lock verification failures
-optimizations !code/simplification/arithmetic,!code/simplification/cast,!field/*,!class/merging/*
-optimizationpasses 5
-allowaccessmodification

# Keep Compose state classes
-keepclassmembers class androidx.compose.runtime.State {
    *;
}
-keepclassmembers class androidx.compose.runtime.MutableState {
    *;
}

# Kotlin Coroutines optimization
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-keepclassmembers class kotlinx.coroutines.** {
    volatile <fields>;
}

# Coil image loading
-keep class coil.** { *; }
-dontwarn coil.**
