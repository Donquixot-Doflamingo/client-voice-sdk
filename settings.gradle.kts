// Gradle build for the Kotlin SDK. Needs internet access to Maven Central / Google (plugins).
// Desktop-only environments without Maven access can use scripts/build-jvm.sh instead.
pluginManagement {
    repositories { gradlePluginPortal(); google(); mavenCentral() }
}
dependencyResolutionManagement {
    repositories { google(); mavenCentral() }
}
rootProject.name = "indic-voice-sdk"
include(":sdk-core", ":sdk-sherpa", ":sdk-jvm", ":proxy")
// The Android library needs the Android SDK; include it only when one is configured.
if (System.getenv("ANDROID_HOME") != null || System.getenv("ANDROID_SDK_ROOT") != null || File(rootDir, "local.properties").exists()) {
    include(":sdk-android")
}
