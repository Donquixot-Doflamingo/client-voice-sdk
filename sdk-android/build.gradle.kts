plugins { id("com.android.library"); kotlin("android") }

android {
    namespace = "dev.indicvoice.android"
    compileSdk = 35
    defaultConfig { minSdk = 26 }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    sourceSets["main"].kotlin.srcDirs(
        "src/main/kotlin",
        "../sdk-core/src/main/kotlin",     // compiled into the AAR so apps get one artifact
        "../sdk-sherpa/src/main/kotlin",   // sherpa bindings come from the AAR below (not the vendored sources)
    )
    sourceSets["main"].assets.srcDirs("../config")  // voice-config.json + catalog.json ship as assets
    packaging { jniLibs.useLegacyPackaging = false }
}

dependencies {
    // sherpa-onnx 1.13.8 Android AAR (native libs for arm64-v8a, armeabi-v7a, x86, x86_64).
    // Fetched by scripts/fetch-sherpa.sh from the GitHub release; or use com.k2fsa.sherpa.onnx:sherpa-onnx-android:<version>.
    implementation(files("../third_party/sherpa-onnx/libs/sherpa-onnx-1.13.8.aar"))
}
