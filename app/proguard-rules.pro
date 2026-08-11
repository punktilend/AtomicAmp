# R8 rules for the release build.
#
# Media3, Room and Compose all ship consumer rules, so most of what is needed arrives with the
# libraries. What follows covers the places this app reaches something by name rather than by
# reference, which R8 cannot see.

# Media3 loads decoder extensions reflectively by class name. Without this the app still builds
# and still plays, then silently loses formats the extension would have handled.
-dontwarn androidx.media3.decoder.**
-keep class androidx.media3.decoder.** { *; }
-keep class androidx.media3.exoplayer.audio.** { *; }

# The service and the boot receiver are named in AndroidManifest.xml. R8 keeps manifest-referenced
# classes on its own, but their session commands are matched as strings at runtime and renaming
# anything they touch would break controls without breaking the build.
-keep class com.atomic.atomicamp.engine.PlaybackService { *; }
-keep class com.atomic.atomicamp.engine.BootReceiver { *; }

# Room entities are constructed by generated code, and the generated code is matched by name.
-keep class com.atomic.atomicamp.app.library.data.** { *; }

# Kotlin metadata, so reflection-based library code keeps working.
-keepattributes *Annotation*, InnerClasses, Signature, RuntimeVisible*

# Crash traces are the only diagnostic this app has on a head unit with no ADB. An obfuscated
# stack trace in the Diagnostics screen would be unreadable exactly when it matters.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
