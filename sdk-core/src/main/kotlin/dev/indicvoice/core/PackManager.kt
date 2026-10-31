package dev.indicvoice.core

import java.io.File

/**
 * Downloads, verifies and locates tier-2 model packs.
 *
 * Layout on disk: `<storageDir>/<packId>/...files...` plus a `.pack.json`
 * marker written after a successful install. A directory that already holds
 * the files the catalog's `model` section refers to also counts as installed,
 * so developers can drop pre-extracted models in by hand.
 */
class PackManager(
    val storageDir: File,
    inlineCatalog: List<PackSpec>,
    catalogUrl: String = "",
    private val log: Logger = Logger.NONE,
) {
    @Volatile var catalogUrl: String = catalogUrl
        private set

    fun setCatalogUrl(url: String) { catalogUrl = url }

    @Volatile private var catalog: Map<String, PackSpec> = inlineCatalog.associateBy { it.id }
    private val downloading = HashSet<String>()

    fun interface Progress { fun onProgress(packId: String, phase: String, bytes: Long, total: Long) }

    init { storageDir.mkdirs() }

    fun catalog(): List<PackSpec> = catalog.values.toList()

    fun spec(id: String): PackSpec? = catalog[id]

    fun dirFor(id: String): File = File(storageDir, id)

    /** Pulls the remote catalog (if configured) and merges it over the inline one. Never throws. */
    fun refreshCatalog(): Boolean {
        if (catalogUrl.isBlank()) return false
        return try {
            val remote = PackCatalog.parse(Http.getText(catalogUrl))
            val merged = LinkedHashMap(catalog)
            for (p in remote) merged[p.id] = p
            catalog = merged
            File(storageDir, ".catalog.cache.json").writeText(Json.stringify(mapOf("packs" to remote.map { it.raw })))
            true
        } catch (e: Exception) {
            log.warn("catalog refresh failed: ${e.message}")
            false
        }
    }

    /** Files the engine needs, resolved from the pack's `model` block. */
    fun requiredFiles(spec: PackSpec): List<File> {
        val dir = dirFor(spec.id)
        val names = ArrayList<String>()
        fun collect(v: Any?) {
            when (v) {
                is String -> if (v.isNotBlank() && !v.startsWith("#")) names += v.split(',').map { it.trim() }.filter { it.isNotEmpty() }
                is Map<*, *> -> v.values.forEach { collect(it) }
                is List<*> -> v.forEach { collect(it) }
            }
        }
        collect(spec.model)
        if (names.isEmpty() && spec.files.isNotEmpty()) names += spec.files.map { it.path }
        return names.map { File(dir, it) }
    }

    fun isInstalled(id: String): Boolean {
        val spec = catalog[id] ?: return false
        val req = requiredFiles(spec)
        if (req.isEmpty()) return File(dirFor(id), ".pack.json").exists()
        return req.all { it.exists() && (it.isDirectory || it.length() > 0) }
    }

    fun installedSizeBytes(id: String): Long = dirFor(id).walkTopDown().filter { it.isFile }.sumOf { it.length() }

    fun status(id: String): Map<String, Any?> {
        val spec = catalog[id]
        return linkedMapOf(
            "id" to id,
            "known" to (spec != null),
            "installed" to isInstalled(id),
            "downloading" to synchronized(downloading) { id in downloading },
            "dir" to dirFor(id).absolutePath,
            "spec" to spec?.toJsonValue(),
        )
    }

    fun listStatus(): List<Map<String, Any?>> = catalog.keys.map { status(it) }

    /** Installs a pack (idempotent). Blocks; call from a background thread. */
    fun install(id: String, progress: Progress? = null): File {
        val spec = catalog[id] ?: throw VoiceException(ErrorCode.PACK_MISSING, "Unknown pack '$id'")
        val dir = dirFor(id)
        if (isInstalled(id)) return dir
        synchronized(downloading) {
            if (id in downloading) throw VoiceException(ErrorCode.PACK_MISSING, "Pack '$id' is already downloading")
            downloading += id
        }
        try {
            val tmp = File(storageDir, ".tmp"); tmp.mkdirs()
            val arch = spec.archive
            if (arch != null && arch.url.isNotBlank()) {
                val ext = when (arch.format) { "tar.bz2" -> ".tar.bz2"; "tar.gz" -> ".tar.gz"; "zip" -> ".zip"; "tar" -> ".tar"; else -> ".bin" }
                val file = File(tmp, "$id$ext")
                log.info("downloading pack $id from ${arch.url}")
                Http.download(arch.url, file, arch.sha256, { b, t -> progress?.onProgress(id, "download", b, if (t > 0) t else spec.sizeBytes) })
                if (arch.format == "file") {
                    dir.mkdirs()
                    val target = File(dir, spec.files.firstOrNull()?.path ?: spec.model.values.filterIsInstance<String>().firstOrNull() ?: file.name)
                    target.parentFile?.mkdirs()
                    file.copyTo(target, overwrite = true)
                } else {
                    val staging = File(tmp, "$id.extract"); staging.deleteRecursively()
                    Archives.extract(file, arch.format, staging, arch.stripTopDir) { path, bytes -> progress?.onProgress(id, "extract", bytes, 0) }
                    dir.deleteRecursively()
                    if (!staging.renameTo(dir)) { staging.copyRecursively(dir, overwrite = true); staging.deleteRecursively() }
                }
                file.delete()
            }
            for (f in spec.files) {
                val target = File(dir, f.path)
                if (target.exists() && target.length() > 0 && (f.sha256 == null || Http.sha256(target).equals(f.sha256, true))) continue
                log.info("downloading ${f.path} for pack $id")
                Http.download(f.url, target, f.sha256, { b, t -> progress?.onProgress(id, "download:${f.path}", b, if (t > 0) t else f.sizeBytes) })
            }
            val missing = requiredFiles(spec).filter { !it.exists() }
            if (missing.isNotEmpty()) throw VoiceException(ErrorCode.PACK_CORRUPT, "Pack '$id' is missing files: ${missing.joinToString { it.name }}")
            File(dir, ".pack.json").writeText(Json.stringify(mapOf("id" to id, "version" to spec.version, "installedAt" to System.currentTimeMillis(), "engine" to spec.engine), true))
            progress?.onProgress(id, "done", 1, 1)
            return dir
        } finally {
            synchronized(downloading) { downloading -= id }
        }
    }

    fun delete(id: String): Boolean = dirFor(id).deleteRecursively()
}

/** Pluggable logger; platforms wire it to Log.d / println. */
interface Logger {
    fun debug(msg: String) {}
    fun info(msg: String) {}
    fun warn(msg: String) {}
    fun error(msg: String, t: Throwable? = null) {}

    companion object {
        val NONE: Logger = object : Logger {}
        fun stdout(level: String = "info"): Logger = object : Logger {
            private val lvl = when (level.lowercase()) { "debug" -> 0; "info" -> 1; "warn" -> 2; else -> 3 }
            override fun debug(msg: String) { if (lvl <= 0) System.err.println("[voice:debug] $msg") }
            override fun info(msg: String) { if (lvl <= 1) System.err.println("[voice] $msg") }
            override fun warn(msg: String) { if (lvl <= 2) System.err.println("[voice:warn] $msg") }
            override fun error(msg: String, t: Throwable?) { System.err.println("[voice:error] $msg${t?.let { " (" + it + ")" } ?: ""}") }
        }
    }
}
