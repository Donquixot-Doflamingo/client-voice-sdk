plugins { kotlin("jvm"); `java-library` }
kotlin { jvmToolchain(17) }
sourceSets {
    main {
        kotlin.srcDirs("src/main/kotlin", "../third_party/sherpa-onnx/kotlin-api", "../third_party/sherpa-onnx/jvm-stubs")
    }
}
dependencies {
    api(project(":sdk-core"))
    // Native runtime per desktop platform (extracted at runtime by SherpaRuntime).
    runtimeOnly(fileTree("../third_party/sherpa-onnx/libs") { include("sherpa-onnx-native-lib-*.jar") })
}
