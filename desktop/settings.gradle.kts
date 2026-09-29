// SonderEye for Windows: its own Gradle build, separate from the Android one.
// ":shared" compiles the Android app's platform-free code (core/ and the downloads in data/)
// straight from app/src/main/java, so both apps run the same code.
pluginManagement {
    repositories {
        gradlePluginPortal()
        google()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "SonderEye-Windows"
include(":shared", ":app")
