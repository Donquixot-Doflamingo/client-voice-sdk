// Gradle build for the Kotlin SDK. Needs internet access to Maven Central / Google (plugins).
// Without Gradle/Maven access, scripts/setup.sh builds and tests the JVM SDK with plain kotlinc.
pluginManagement {
    repositories { gradlePluginPortal(); google(); mavenCentral() }
}
dependencyResolutionManagement {
    repositories { google(); mavenCentral() }
}
rootProject.name = "client-voice-sdk"
include(":sdk-core", ":sdk-sherpa", ":sdk-jvm")
// The Android library needs the Android SDK; include it only when one is configured.
if (System.getenv("ANDROID_HOME") != null || System.getenv("ANDROID_SDK_ROOT") != null || File(rootDir, "local.properties").exists()) {
    include(":sdk-android")
}
