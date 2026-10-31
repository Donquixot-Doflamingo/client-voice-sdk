package dev.indicvoice.sherpa

import dev.indicvoice.core.Availability
import dev.indicvoice.core.Logger
import java.io.File

/**
 * Loads the sherpa-onnx native libraries.
 *
 * Android: the AAR ships the .so files; `System.loadLibrary` (done by the
 * sherpa Kotlin API) just works.
 *
 * JVM: the `sherpa-onnx-native-lib-<os>-<arch>` jar carries the libraries as
 * resources under `sherpa-onnx/native/<os>-<arch>/`. We extract them once to
 * a cache directory and put that directory on `java.library.path` *before*
 * the first JNI load (the JDK reads the property lazily, so setting it early
 * in main() works on JDK 12+).
 */
object SherpaRuntime {
    const val VERSION = "1.13.8"

    @Volatile private var prepared = false
    @Volatile private var failure: String? = null

    fun platformKey(): String {
        val os = System.getProperty("os.name", "").lowercase()
        val arch = System.getProperty("os.arch", "").lowercase()
        val osKey = when {
            os.contains("mac") || os.contains("darwin") -> "osx"
            os.contains("win") -> "win"
            else -> "linux"
        }
        val archKey = when (arch) {
            "aarch64", "arm64" -> "aarch64"
            "x86_64", "amd64" -> "x64"
            else -> arch
        }
        return "$osKey-$archKey"
    }

    fun isAndroid(): Boolean = System.getProperty("java.vendor", "").contains("Android", true) ||
        System.getProperty("java.vm.name", "").contains("Dalvik", true) || System.getProperty("java.runtime.name", "").contains("Android", true)

    /** Call once at startup on the JVM. Safe to call repeatedly. */
    @Synchronized
    fun prepareJvm(cacheDir: File? = null, log: Logger = Logger.NONE): Availability {
        if (prepared) return Availability.YES
        failure?.let { return Availability.no(it) }
        if (isAndroid()) { prepared = true; return Availability.YES }
        try {
            val key = platformKey()
            val dir = cacheDir ?: File(System.getProperty("user.home"), ".indicvoice/native/$VERSION/$key")
            val libNames = when {
                key.startsWith("osx") -> listOf("libonnxruntime.dylib", "libsherpa-onnx-jni.dylib")
                key.startsWith("win") -> listOf("onnxruntime.dll", "sherpa-onnx-jni.dll")
                else -> listOf("libonnxruntime.so", "libsherpa-onnx-jni.so")
            }
            // 1. An explicit directory via system property or env wins.
            val explicit = System.getProperty("indicvoice.nativeDir") ?: System.getenv("INDICVOICE_NATIVE_DIR")
            val useDir = if (explicit != null && libNames.all { File(explicit, it).exists() }) File(explicit) else dir
            if (!libNames.all { File(useDir, it).exists() }) {
                useDir.mkdirs()
                for (name in libNames) {
                    val res = "/sherpa-onnx/native/$key/$name"
                    val ins = SherpaRuntime::class.java.getResourceAsStream(res)
                        ?: throw IllegalStateException("Native library $name for $key not found on the classpath (add sherpa-onnx-native-lib-$key-$VERSION.jar) and not in ${useDir.absolutePath}")
                    val tmp = File(useDir, "$name.part")
                    ins.use { i -> tmp.outputStream().use { o -> i.copyTo(o) } }
                    tmp.renameTo(File(useDir, name))
                    log.info("extracted $name to $useDir")
                }
            }
            val current = System.getProperty("java.library.path", "")
            if (!current.split(File.pathSeparator).contains(useDir.absolutePath)) {
                System.setProperty("java.library.path", useDir.absolutePath + File.pathSeparator + current)
            }
            // Load onnxruntime explicitly so the JNI lib's dependency resolves even without rpath.
            System.load(File(useDir, libNames[0]).absolutePath)
            System.load(File(useDir, libNames[1]).absolutePath)
            prepared = true
            return Availability.YES
        } catch (e: Throwable) {
            failure = "sherpa-onnx native runtime unavailable: ${e.message}"
            log.error(failure!!, e)
            return Availability.no(failure!!)
        }
    }

    fun availability(): Availability = if (prepared || isAndroid()) Availability.YES else failure?.let { Availability.no(it) } ?: prepareJvm()

    /**
     * Called from the (JVM-patched) sherpa Kotlin API in place of
     * `System.loadLibrary("sherpa-onnx-jni")`. On Android the unpatched AAR
     * classes do the normal loadLibrary and this is never reached.
     */
    @JvmStatic
    fun loadNative() {
        if (isAndroid()) { System.loadLibrary("sherpa-onnx-jni"); return }
        val av = prepareJvm()
        if (!av.available) throw UnsatisfiedLinkError(av.reason)
    }
}
