pluginManagement {
    repositories {
        google {
            mavenContent {
                includeGroupAndSubgroups("androidx")
                includeGroupAndSubgroups("com.android")
                includeGroupAndSubgroups("com.google")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        google {
            mavenContent {
                includeGroupAndSubgroups("androidx")
                includeGroupAndSubgroups("com.android")
                includeGroupAndSubgroups("com.google")
            }
        }
        mavenCentral()
    }
}

// Fresh-clone friendliness: when neither local.properties nor an
// ANDROID_HOME/ANDROID_SDK_ROOT environment variable provides an SDK
// location, generate local.properties from the platform's default
// install path so the build works on a new machine without setup.
run {
    val localPropertiesFile = File(rootDir, "local.properties")
    val environmentSdkPath = System.getenv("ANDROID_HOME")
        ?: System.getenv("ANDROID_SDK_ROOT")
    if (!localPropertiesFile.exists() && environmentSdkPath == null) {
        val userHome = System.getProperty("user.home")
        val osName = System.getProperty("os.name").lowercase()
        val defaultSdkDirectory = when {
            osName.contains("mac") ->
                File(userHome, "Library/Android/sdk")
            osName.contains("windows") ->
                File(
                    System.getenv("LOCALAPPDATA")
                        ?: "$userHome/AppData/Local",
                    "Android/Sdk",
                )
            else -> File(userHome, "Android/Sdk")
        }
        if (defaultSdkDirectory.exists()) {
            localPropertiesFile.writeText(
                "# Generated automatically from the default SDK install\n" +
                    "# location. Machine-specific; never commit this file.\n" +
                    "sdk.dir=" +
                    defaultSdkDirectory.absolutePath.replace("\\", "/") +
                    "\n",
            )
        }
    }
}

rootProject.name = "Android-App-Template"

// Layered modules; the compiler is the arbiter of the layering:
// :core (Kotlin JVM: managers, ports, api; android.* is not even on
// the classpath) <- :ui (Android library: views, viewmodels,
// navigation; sees only :core) <- :app (composition root, edge
// adapters, Application/Activity; sees both).
include(":app")
include(":core")
include(":ui")
