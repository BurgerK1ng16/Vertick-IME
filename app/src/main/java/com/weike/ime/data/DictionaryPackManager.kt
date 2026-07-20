package com.weike.ime.data

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.io.ByteArrayInputStream
import java.security.MessageDigest
import java.security.Signature
import java.security.cert.CertificateFactory
import android.util.Base64
import java.util.concurrent.TimeUnit
import java.util.zip.ZipFile

data class DictionaryPackDescriptor(
    val id: String,
    val displayName: String,
    val version: String,
    val sizeBytes: Long,
    val sha256: String,
    /** COS/CDN is listed first; GitHub Release is the automatic fallback. */
    val urls: List<String>,
    val requires: List<String> = emptyList(),
    val license: String = "",
    val enabledByDefault: Boolean = false
)

data class ImportedDictionaryEntry(val text: String, val code: String)

data class DictionaryDownloadProgress(
    val downloadedBytes: Long,
    val totalBytes: Long
) {
    val fraction: Float = if (totalBytes > 0) (downloadedBytes.toFloat() / totalBytes).coerceIn(0f, 1f) else 0f
}

/**
 * Installs complete, host-built Rime bundles. The app never deploys a YAML
 * schema or compiles a table. A package is extracted to a versioned directory,
 * validated, then atomically copied into the active slot.
 */
class DictionaryPackManager(context: Context) {
    private val appContext = context.applicationContext
    private val root = File(appContext.filesDir, "rime/packs")
    private val active = File(appContext.filesDir, "rime/active_bundle")
    private val importedFile = File(appContext.filesDir, "rime/user/imported.tsv")
    private val client = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .callTimeout(2, TimeUnit.MINUTES)
        .build()

    suspend fun fetchCatalog(): List<DictionaryPackDescriptor> = withContext(Dispatchers.IO) {
        var lastError: Throwable? = null
        for (url in CATALOG_URLS) {
            val result = runCatching {
                val request = Request.Builder().url(url).build()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) error("Dictionary catalog HTTP ${response.code}")
                    parseCatalog(verifySignedCatalog(response.body?.string().orEmpty()))
                }
            }
            if (result.isSuccess) return@withContext result.getOrThrow()
            lastError = result.exceptionOrNull()
        }
        throw lastError ?: IllegalStateException("Dictionary catalog unavailable")
    }

    suspend fun downloadAndActivate(
        pack: DictionaryPackDescriptor,
        onProgress: (DictionaryDownloadProgress) -> Unit = {}
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            require(PACK_ID.matches(pack.id)) { "Invalid dictionary package id" }
            require(PACK_VERSION.matches(pack.version)) { "Invalid dictionary package version" }
            require(pack.urls.isNotEmpty()) { "No download URL for dictionary package" }
            require(pack.sha256.matches(Regex("[a-fA-F0-9]{64}"))) { "Invalid dictionary package checksum" }
            val versionDir = File(root, "${pack.id}/${pack.version}")
            val partial = File(root, ".${pack.id}-${pack.version}.zip.part")
            partial.parentFile?.mkdirs()
            downloadWithFallback(pack.urls, partial, onProgress)
            require(pack.sizeBytes <= 0L || partial.length() == pack.sizeBytes) { "Dictionary package size check failed" }
            require(pack.sha256.equals(sha256(partial), ignoreCase = true)) { "Dictionary package SHA-256 check failed" }
            val staging = File(root, ".staging-${pack.id}-${pack.version}")
            staging.deleteRecursively()
            staging.mkdirs()
            try {
                extractSafely(partial, staging)
                val missingRoots = ROOT_FILES.filterNot { File(staging, it).isFile }
                val missingBuild = BUILD_FILES.filterNot { File(staging, "build/$it").isFile }
                val missingExtras = AUXILIARY_FILES.filterNot { File(staging, it).isFile }
                require(missingRoots.isEmpty()) { "Dictionary package is missing schema files: $missingRoots" }
                require(missingBuild.isEmpty()) { "Dictionary package is missing prebuilt files: $missingBuild" }
                require(missingExtras.isEmpty()) { "Dictionary package is missing enhancement resources: $missingExtras" }
                require(File(staging, "build/weike_pinyin.table.bin").length() >= MIN_TABLE_BYTES) {
                    "Dictionary package table is invalid"
                }
                versionDir.parentFile?.mkdirs()
                versionDir.deleteRecursively()
                require(staging.renameTo(versionDir)) { "Unable to install dictionary package" }
                atomicActivate(versionDir)
            } finally {
                staging.deleteRecursively()
                partial.delete()
            }
            Unit
        }
    }

    suspend fun downloadAndEnableWanxiang(
        onProgress: (DictionaryDownloadProgress) -> Unit = {}
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            require(hasEnhancedDictionary()) { "Download the enhanced dictionary first" }
            val destination = File(appContext.filesDir, "rime/user/$WANXIANG_FILE")
            val partial = File(destination.parentFile, ".$WANXIANG_FILE.part")
            destination.parentFile?.mkdirs()
            try {
                downloadWithFallback(listOf(WANXIANG_URL), partial, onProgress)
                require(partial.length() == WANXIANG_SIZE_BYTES) { "Wanxiang model size check failed" }
                require(sha256(partial).equals(WANXIANG_SHA256, ignoreCase = true)) { "Wanxiang model SHA-256 check failed" }
                if (destination.exists()) destination.delete()
                require(partial.renameTo(destination)) { "Unable to install Wanxiang model" }
                applyWanxiangSchema()
            } finally {
                partial.delete()
            }
            Unit
        }
    }

    fun hasEnhancedDictionary(): Boolean = activeBundleDir()?.let { bundle ->
        File(bundle, "build/melt_eng.table.bin").isFile
    } == true

    fun isWanxiangEnabled(): Boolean = File(appContext.filesDir, "rime/user/$WANXIANG_FILE").isFile &&
        File(appContext.filesDir, "rime/user/.wanxiang_enabled").isFile

    /** Reapply the grammar-aware schema after an enhanced package switch. */
    fun restoreWanxiangSchemaIfEnabled(): Boolean {
        if (!isWanxiangEnabled() || !hasEnhancedDictionary()) return false
        applyWanxiangSchema()
        return true
    }

    fun disableWanxiang(): Result<Unit> = runCatching {
        val userDir = File(appContext.filesDir, "rime/user")
        File(userDir, WANXIANG_FILE).delete()
        File(userDir, ".wanxiang_enabled").delete()
        val source = activeBundleDir()?.let { File(it, "weike_pinyin.schema.yaml") }
            ?.takeIf(File::isFile)
        if (source != null) {
            copyFile(source, File(appContext.filesDir, "rime/shared/weike_pinyin.schema.yaml"))
            copyFile(source, File(appContext.filesDir, "rime/shared/build/weike_pinyin.schema.yaml"))
            copyFile(source, File(userDir, "build/weike_pinyin.schema.yaml"))
        }
    }

    suspend fun importLocalDictionary(uri: Uri): Result<Int> = withContext(Dispatchers.IO) {
        runCatching {
            val bytes = appContext.contentResolver.openInputStream(uri)?.use { input ->
                input.readBytes().also { require(it.size <= MAX_IMPORT_BYTES) { "Dictionary file is too large" } }
            } ?: error("Unable to read dictionary file")
            val imported = if (looksLikeScel(bytes, uri)) parseScel(bytes) else parseTextDictionary(bytes.toString(Charsets.UTF_8))
            require(imported.isNotEmpty()) { "No usable entries found in dictionary" }
            importedFile.parentFile?.mkdirs()
            val merged = (importedEntries() + imported)
                .asSequence()
                .map { ImportedDictionaryEntry(cleanText(it.text), normalizeCode(it.code)) }
                .filter { it.text.isNotBlank() && it.code.isNotBlank() }
                .distinctBy { it.text }
                .take(MAX_IMPORTED_ENTRIES)
                .toList()
            importedFile.writeText(merged.joinToString("\n") { "${it.text}\t${it.code}" } + "\n")
            imported.size
        }
    }

    /** Entries are application-side priority candidates and take effect immediately. */
    fun importedEntries(): List<ImportedDictionaryEntry> {
        if (!importedFile.isFile) return emptyList()
        return runCatching {
            importedFile.useLines { lines ->
                lines.mapNotNull { line ->
                    val fields = line.split('\t')
                    val text = fields.getOrNull(0)?.let(::cleanText).orEmpty()
                    val code = fields.getOrNull(1)?.let(::normalizeCode).orEmpty()
                    if (text.isBlank() || code.isBlank()) null else ImportedDictionaryEntry(text, code)
                }.take(MAX_IMPORTED_ENTRIES).toList()
            }
        }.getOrDefault(emptyList())
    }

    fun activeBundleDir(): File? = active.takeIf { it.isDirectory }

    fun installedPacks(): List<File> = root.listFiles()?.asSequence()
        ?.filter { it.isDirectory && !it.name.startsWith('.') }
        ?.flatMap { it.listFiles()?.asSequence().orEmpty() }
        ?.filter(File::isDirectory)
        ?.sortedByDescending(File::lastModified)
        ?.toList().orEmpty()

    private fun downloadWithFallback(
        urls: List<String>,
        destination: File,
        onProgress: (DictionaryDownloadProgress) -> Unit
    ) {
        var lastError: Throwable? = null
        for (url in urls.distinct()) {
            val outcome = runCatching {
                require(url.startsWith("https://")) { "Dictionary package URL must use HTTPS" }
                val request = Request.Builder().url(url).build()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) error("HTTP ${response.code}")
                    response.body?.byteStream()?.use { input ->
                        val total = response.body?.contentLength()?.takeIf { it > 0 } ?: -1L
                        FileOutputStream(destination).use { output ->
                            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                            var downloaded = 0L
                            while (true) {
                                val count = input.read(buffer)
                                if (count < 0) break
                                output.write(buffer, 0, count)
                                downloaded += count
                                onProgress(DictionaryDownloadProgress(downloaded, total))
                            }
                        }
                    } ?: error("Empty dictionary package")
                }
            }
            if (outcome.isSuccess) return
            destination.delete()
            lastError = outcome.exceptionOrNull()
        }
        throw lastError ?: IllegalStateException("Dictionary package download failed")
    }

    private fun atomicActivate(bundle: File) {
        val parent = active.parentFile ?: error("Missing dictionary root")
        val next = File(parent, "active_bundle.next")
        val old = File(parent, "active_bundle.old")
        next.deleteRecursively()
        old.deleteRecursively()
        require(bundle.copyRecursively(next, overwrite = true)) { "Unable to stage dictionary package" }
        if (active.exists()) require(active.renameTo(old)) { "Unable to preserve current dictionary package" }
        if (!next.renameTo(active)) {
            if (old.exists()) old.renameTo(active)
            error("Unable to activate dictionary package")
        }
        old.deleteRecursively()
    }

    private fun applyWanxiangSchema() {
        val userDir = File(appContext.filesDir, "rime/user")
        val sharedDir = File(appContext.filesDir, "rime/shared")
        val destinations = listOf(
            File(sharedDir, "weike_pinyin.schema.yaml"),
            File(sharedDir, "build/weike_pinyin.schema.yaml"),
            File(userDir, "build/weike_pinyin.schema.yaml")
        )
        destinations.forEach { destination ->
            destination.parentFile?.mkdirs()
            appContext.assets.open("rime/wanxiang/weike_pinyin.schema.yaml").use { input ->
                destination.outputStream().use(input::copyTo)
            }
        }
        File(userDir, ".wanxiang_enabled").apply {
            parentFile?.mkdirs()
            writeText("1")
        }
    }

    private fun copyFile(source: File, destination: File) {
        destination.parentFile?.mkdirs()
        source.inputStream().use { input -> destination.outputStream().use(input::copyTo) }
    }

    private fun extractSafely(zipFile: File, destination: File) {
        val rootPath = destination.canonicalFile.toPath()
        ZipFile(zipFile).use { archive ->
            val entries = archive.entries()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                // Some Windows ZIP tools emit backslashes. Normalize them before
                // validation so the same signed package works on Android/Linux.
                val relativePath = entry.name.replace('\\', '/')
                require(!relativePath.startsWith('/')) { "Unsafe dictionary package path" }
                val target = File(destination, relativePath)
                require(target.canonicalFile.toPath().startsWith(rootPath)) { "Unsafe dictionary package path" }
                if (entry.isDirectory) target.mkdirs() else {
                    target.parentFile?.mkdirs()
                    archive.getInputStream(entry).use { input ->
                        FileOutputStream(target).use(input::copyTo)
                    }
                }
            }
        }
    }

    private fun parseTextDictionary(text: String): List<ImportedDictionaryEntry> = text.lineSequence()
        .map(String::trim)
        .filter { it.isNotBlank() && !it.startsWith('#') && it != "---" && it != "..." }
        .mapNotNull { line ->
            val fields = line.split(Regex("\\s+"))
            val term = fields.getOrNull(0)?.let(::cleanText).orEmpty()
            val code = fields.getOrNull(1)?.let(::normalizeCode).orEmpty()
            if (term.isBlank() || code.isBlank()) null else ImportedDictionaryEntry(term, code)
        }.take(MAX_IMPORTED_ENTRIES).toList()

    /** Common Sogou CEL binary layout. Unsupported/corrupt records are skipped. */
    private fun parseScel(bytes: ByteArray): List<ImportedDictionaryEntry> {
        require(bytes.size >= SCEL_WORD_OFFSET) { "Invalid SCEL dictionary" }
        val pinyinMap = HashMap<Int, String>()
        var offset = SCEL_PINYIN_OFFSET
        while (offset + 3 < SCEL_WORD_OFFSET && offset + 3 < bytes.size) {
            val id = u16(bytes, offset)
            val length = bytes[offset + 2].toInt() and 0xff
            offset += 3
            if (length <= 0 || offset + length > SCEL_WORD_OFFSET || offset + length > bytes.size) break
            val value = decodeUtf16(bytes, offset, length)
            if (value.isNotBlank()) pinyinMap[id] = normalizeCode(value)
            offset += length
        }
        val entries = ArrayList<ImportedDictionaryEntry>()
        offset = SCEL_WORD_OFFSET
        while (offset + 4 <= bytes.size && entries.size < MAX_IMPORTED_ENTRIES) {
            val syllableCount = u16(bytes, offset)
            val idsBytes = u16(bytes, offset + 2)
            offset += 4
            if (syllableCount <= 0 || idsBytes <= 0 || offset + idsBytes + 2 > bytes.size) break
            val code = buildString {
                var cursor = offset
                val end = offset + idsBytes
                while (cursor + 1 < end) {
                    pinyinMap[u16(bytes, cursor)]?.takeIf(String::isNotBlank)?.let {
                        if (isNotEmpty()) append(' ')
                        append(it)
                    }
                    cursor += 2
                }
            }
            offset += idsBytes
            val wordCount = u16(bytes, offset)
            offset += 2
            repeat(wordCount.coerceAtMost(4096)) {
                if (offset + 4 > bytes.size) return@repeat
                val wordBytes = u16(bytes, offset)
                offset += 2
                if (wordBytes <= 0 || offset + wordBytes + 2 > bytes.size) return@repeat
                val word = cleanText(decodeUtf16(bytes, offset, wordBytes))
                offset += wordBytes
                val extraBytes = u16(bytes, offset)
                offset += 2
                if (extraBytes < 0 || offset + extraBytes > bytes.size) return@repeat
                offset += extraBytes
                if (word.isNotBlank() && code.isNotBlank()) entries += ImportedDictionaryEntry(word, code)
            }
        }
        return entries
    }

    private fun looksLikeScel(bytes: ByteArray, uri: Uri): Boolean =
        uri.lastPathSegment?.endsWith(".scel", ignoreCase = true) == true ||
            (bytes.size > SCEL_WORD_OFFSET && bytes[0].toInt() and 0xff == 0x40)

    private fun u16(bytes: ByteArray, offset: Int): Int = ByteBuffer.wrap(bytes, offset, 2)
        .order(ByteOrder.LITTLE_ENDIAN).short.toInt() and 0xffff

    private fun decodeUtf16(bytes: ByteArray, offset: Int, length: Int): String =
        bytes.copyOfRange(offset, offset + length).toString(Charsets.UTF_16LE).trim('\u0000', ' ', '\t', '\r', '\n')

    private fun cleanText(value: String): String = value.replace(Regex("[\\t\\r\\n]+"), " ").trim().take(MAX_TERM_LENGTH)
    private fun normalizeCode(value: String): String = value.lowercase().replace(Regex("[^a-z' ]"), " ")
        .trim().replace(Regex("\\s+"), " ")

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /**
     * The server sends an envelope with a base64 UTF-8 JSON payload and a
     * detached Ed25519 signature. Keeping the exact payload bytes avoids JSON
     * canonicalization differences between CI and Android.
     */
    private fun verifySignedCatalog(envelope: String): String {
        val outer = JSONObject(envelope)
        val payload = Base64.decode(outer.getString("payload"), Base64.NO_WRAP)
        val signature = Base64.decode(outer.getString("signature"), Base64.NO_WRAP)
        val certificate = CertificateFactory.getInstance("X.509").generateCertificate(
            ByteArrayInputStream(Base64.decode(MANIFEST_CERTIFICATE_BASE64, Base64.NO_WRAP))
        )
        val verifier = Signature.getInstance("Ed25519")
        verifier.initVerify(certificate.publicKey)
        verifier.update(payload)
        require(verifier.verify(signature)) { "Dictionary catalog signature verification failed" }
        return payload.toString(Charsets.UTF_8)
    }

    private fun parseCatalog(raw: String): List<DictionaryPackDescriptor> {
        val source = JSONObject(raw)
        val array = source.optJSONArray("packs") ?: JSONArray()
        return buildList {
            for (index in 0 until array.length()) {
                val item = array.getJSONObject(index)
                val urls = item.optJSONArray("urls")?.let { values ->
                    (0 until values.length()).mapNotNull { values.optString(it).takeIf(String::isNotBlank) }
                }.orEmpty()
                add(DictionaryPackDescriptor(
                    id = item.getString("id"),
                    displayName = item.optString("displayName", item.getString("id")),
                    version = item.getString("version"),
                    sizeBytes = item.optLong("sizeBytes", 0L),
                    sha256 = item.getString("sha256"),
                    urls = urls,
                    requires = item.optJSONArray("requires")?.let { values ->
                        (0 until values.length()).map { values.getString(it) }
                    }.orEmpty(),
                    license = item.optString("license"),
                    enabledByDefault = item.optBoolean("enabledByDefault", false)
                ))
            }
        }
    }

    companion object {
        // Add the COS/CDN manifest URL before the GitHub URL when it is ready.
        // Every source must serve the same signed envelope.
        val CATALOG_URLS = listOf(
            "https://github.com/BurgerK1ng16/Vertick-IME/releases/latest/download/manifest.json"
        )
        val ROOT_FILES = listOf("default.yaml", "weike_pinyin.schema.yaml", "weike_t9.schema.yaml")
        val BUILD_FILES = listOf(
            "weike_pinyin.table.bin",
            "weike_pinyin.prism.bin",
            "weike_t9.prism.bin",
            "weike_pinyin.reverse.bin",
            "melt_eng.table.bin"
        )
        val AUXILIARY_FILES = listOf("opencc/emoji.json", "opencc/emoji.txt", "opencc/others.txt")
        val BUNDLE_FILES = ROOT_FILES + BUILD_FILES + AUXILIARY_FILES
        val ENHANCED_PACK = DictionaryPackDescriptor(
            id = "rime-ice-enhanced",
            displayName = "Enhanced offline dictionary",
            version = "2026.07.20",
            sizeBytes = 26_618_785L,
            sha256 = "b870605c963d7c8caa05ed9e2c5e095475014d98dbeccd2cb4782e3659463447",
            urls = listOf("https://vertick-1257312282.cos.ap-guangzhou.myqcloud.com/rime-ice-enhanced-2026.07.20.zip"),
            license = "GPL-3.0-or-later",
            enabledByDefault = false
        )
        private const val WANXIANG_FILE = "wanxiang-lts-zh-hans.gram"
        private const val WANXIANG_URL = "https://vertick-1257312282.cos.ap-guangzhou.myqcloud.com/wanxiang-lts-zh-hans.gram"
        // Pinned to the COS artifact, which differs by 2 KiB from the
        // upstream release asset and must therefore use its own checksum.
        private const val WANXIANG_SIZE_BYTES = 420_012_076L
        private const val WANXIANG_SHA256 = "099b3e378bfe3d65201a82bfd10b0479602f2492f7328b1ebe97a8d781056340"
        private val PACK_ID = Regex("[a-z0-9][a-z0-9_-]{0,63}")
        private val PACK_VERSION = Regex("[A-Za-z0-9._-]{1,64}")
        private const val MIN_TABLE_BYTES = 1_000_000L
        private const val MAX_IMPORT_BYTES = 20 * 1024 * 1024
        private const val MAX_IMPORTED_ENTRIES = 20_000
        private const val MAX_TERM_LENGTH = 64
        private const val SCEL_PINYIN_OFFSET = 0x1540
        private const val SCEL_WORD_OFFSET = 0x2628
        // Public certificate only. The private signing key is supplied to CI as
        // DICTIONARY_MANIFEST_KEYSTORE_BASE64 and is never part of this APK.
        private const val MANIFEST_CERTIFICATE_BASE64 =
            "MIH6MIGtoAMCAQICCBCi1D/P06loMAUGAytlcDASMRAwDgYDVQQDEwdWZXJ0aWNrMB4XDTI2MDcyMDEwNTk0MloXDTM2MDcxNzEwNTk0MlowEjEQMA4GA1UEAxMHVmVy" +
                "dGljazAqMAUGAytlcAMhADvwuZ44VVp9dEQ4iT+LHLDcpcydCayrK79ZvF7T6LPKoyEwHzAdBgNVHQ4EFgQUNGzVhv90nWcPvPteqzl9HrtT/howBQYDK2VwA0EADDjL" +
                "Pjwn411FeneNT84S9WhYMs7yavJBt+cz+mmhpU/qKyPvobQnUpL9Hv59mzHIrcFcLC/mDH3t8QUdu7wwAw=="
    }
}
