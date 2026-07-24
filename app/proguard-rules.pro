# App-specific R8 rules, appended to AGP's bundled
# proguard-android-optimize.txt (selected via getDefaultProguardFile
# in app/build.gradle.kts). Library keep rules (kotlinx-serialization,
# Ktor, Hilt, Compose) ship as consumer rules inside those libraries
# and never belong here; this file holds only what THIS app's own
# code needs.

# LogManager resolves each logging call site by walking the current
# stack and skipping its own frames BY SIMPLE CLASS NAME. Renaming
# LogManager or its file facade would defeat that filter and
# misattribute every log line to a logger-internal frame, so their
# names are kept (keepnames renames nothing but removes nothing).
-keepnames class com.mattmooneyham.base.android.managers.logManager.LogManager
-keepnames class com.mattmooneyham.base.android.managers.logManager.LogManagerKt

# Keep file names and line numbers in stack traces so crash-report
# retracing and the log file's call-site column stay useful in
# release builds.
-keepattributes SourceFile,LineNumberTable
