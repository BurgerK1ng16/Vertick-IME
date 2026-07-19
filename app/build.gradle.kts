import java.util.Properties
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.text.Normalizer
import java.util.Locale

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.kapt")
}

val releaseProperties = Properties().apply {
    val file = rootProject.file("keystore.properties")
    if (file.exists()) file.inputStream().use(::load)
}

android {
    namespace = "com.weike.ime"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.weike.ime"
        minSdk = 35
        targetSdk = 36
        versionCode = 17
        versionName = "1.3.2"

        ndk {
            // This app is intentionally built only for the user's Xiaomi 17 Pro.
            abiFilters += "arm64-v8a"
        }
    }

    signingConfigs {
        create("release") {
            val storePath = releaseProperties.getProperty("storeFile").orEmpty()
            if (storePath.isNotBlank()) {
                storeFile = rootProject.file(storePath)
                storePassword = releaseProperties.getProperty("storePassword")
                keyAlias = releaseProperties.getProperty("keyAlias")
                keyPassword = releaseProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        getByName("release") {
            isDebuggable = false
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (releaseProperties.getProperty("storeFile").orEmpty().isNotBlank()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    sourceSets {
        getByName("main").apply {
            // The legacy librime JNI/table path is not part of the new decoder.
            // Keep its sources in the repository for attribution, not in the APK.
            jniLibs.srcDirs(emptyList<String>())
            assets.setSrcDirs(
                listOf(
                    layout.buildDirectory.dir("generated/runtimeAssets"),
                    layout.buildDirectory.dir("generated/pinyinAssets")
                )
            )
        }
    }

    externalNativeBuild {
        cmake { path = file("src/main/cpp/CMakeLists.txt") }
    }

    androidResources {
        // Jieba models are copied verbatim into the private native-data directory.
        noCompress += setOf("utf8", "bin")
    }

    packaging {
        jniLibs {
            excludes += "**/librime_jni.so"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

private data class PinyinBuildEntry(val text: String, val code: String, val initials: String, val t9: String, val weight: Int)

private fun normalizedPinyin(value: String): String = Normalizer.normalize(value.lowercase(Locale.ROOT), Normalizer.Form.NFD)
    .filter { it in 'a'..'z' }

private fun toT9(value: String): String = value.map { character ->
    when (character) {
        in 'a'..'c' -> '2'
        in 'd'..'f' -> '3'
        in 'g'..'i' -> '4'
        in 'j'..'l' -> '5'
        in 'm'..'o' -> '6'
        in 'p'..'s' -> '7'
        in 't'..'v' -> '8'
        in 'w'..'z' -> '9'
        else -> character
    }
}.joinToString("")

/**
 * Builds a direct, memory-mappable dictionary. Runtime never parses YAML and
 * never compiles Rime tables. Every index record contains the top weighted
 * candidates for one full-Pinyin, initials, or T9 key.
 */
val generateFullPinyinIndex = tasks.register("generateFullPinyinIndex") {
    val dictionaryDir = layout.projectDirectory.dir("src/main/assets/rime/cn_dicts")
    val readingData = layout.projectDirectory.file("src/main/assets/pinyin/pinyin.txt")
    val essayData = layout.projectDirectory.file("src/main/assets/language/essay-zh-hans.txt")
    val output = layout.buildDirectory.file("generated/pinyinAssets/pinyin/full_pinyin_index.bin")
    inputs.dir(dictionaryDir)
    inputs.file(readingData)
    inputs.file(essayData)
    outputs.file(output)

    doLast {
        val readings = HashMap<Char, String>(35_000)
        val readingRegex = Regex("^U\\+([0-9A-Fa-f]+):\\s*(.+)$")
        readingData.asFile.bufferedReader().useLines { lines ->
            lines.forEach { line ->
                val match = readingRegex.matchEntire(line.substringBefore('#').trim()) ?: return@forEach
                val codePoint = match.groupValues[1].toIntOrNull(16) ?: return@forEach
                if (codePoint !in Char.MIN_VALUE.code..Char.MAX_VALUE.code) return@forEach
                val character = codePoint.toChar()
                val code = normalizedPinyin(match.groupValues[2].substringBefore(','))
                if (code.isNotBlank()) readings[character] = code
            }
        }

        // The essay list is a language-frequency source, not a pronunciation
        // source. It intentionally never creates a new Pinyin key from per-char
        // readings: doing so produces bad candidates for polyphonic phrases.
        val essayWeights = HashMap<String, Int>(400_000)
        essayData.asFile.bufferedReader().useLines { lines ->
            lines.forEach lineLoop@ { line ->
                if (line.isBlank() || line.startsWith('#')) return@lineLoop
                val columns = line.split('\t')
                if (columns.size < 2) return@lineLoop
                val text = columns[0].trim()
                val weight = columns[1].trim().toIntOrNull()?.coerceAtLeast(1) ?: return@lineLoop
                if (text.isNotBlank()) essayWeights[text] = maxOf(essayWeights[text] ?: 0, weight)
            }
        }

        val sources = listOf("8105.dict.yaml", "base.dict.yaml", "ext.dict.yaml", "tencent.dict.yaml", "others.dict.yaml")

        val temporaryDirectory = layout.buildDirectory.dir("tmp/fullPinyinIndex").get().asFile.apply {
            deleteRecursively()
            mkdirs()
        }
        val bucketKeys = ("abcdefghijklmnopqrstuvwxyz".toList() + ('2'..'9').toList())
        fun bucketFor(key: String): Char = key.firstOrNull()?.takeIf { it in bucketKeys } ?: 'z'
        val bucketStreams = HashMap<String, DataOutputStream>()
        fun writeTemporary(index: Int, key: String, textId: Int, weight: Int) {
            val bucket = bucketFor(key)
            val streamKey = "$index-$bucket"
            val stream = bucketStreams.getOrPut(streamKey) {
                DataOutputStream(BufferedOutputStream(File(temporaryDirectory, "$streamKey.bin").outputStream(), 64 * 1024))
            }
            stream.writeUTF(key)
            stream.writeInt(textId)
            stream.writeInt(weight)
        }

        fun forEachEntry(consumer: (PinyinBuildEntry) -> Unit) {
            sources.forEach { filename ->
                val fallbackReading = filename == "tencent.dict.yaml"
                dictionaryDir.file(filename).asFile.bufferedReader().useLines { lines ->
                    lines.forEach lineLoop@ { line ->
                        if (line.isBlank() || line.startsWith('#') || line.startsWith("---") || line == "...") return@lineLoop
                        val columns = line.split('\t')
                        if (columns.size < 2) return@lineLoop
                        val text = columns[0].trim()
                        if (text.isBlank() || text.length > 16) return@lineLoop
                        val rawCode = if (fallbackReading) "" else columns[1]
                        val syllables = rawCode.split(Regex("\\s+")).map(::normalizedPinyin).filter(String::isNotBlank)
                            .ifEmpty { if (fallbackReading) text.mapNotNull(readings::get) else emptyList() }
                        if (syllables.isEmpty()) return@lineLoop
                        val code = syllables.joinToString("")
                        if (code.length > 48) return@lineLoop
                        val weightColumn = if (fallbackReading) columns.getOrNull(1) else columns.getOrNull(2)
                        val sourceWeight = weightColumn?.toIntOrNull()?.coerceAtLeast(1) ?: 100
                        consumer(PinyinBuildEntry(
                            text,
                            code,
                            syllables.joinToString("") { it.take(1) },
                            toT9(code),
                            maxOf(sourceWeight, essayWeights[text] ?: 0)
                        ))
                    }
                }
            }
        }

        val textIds = HashMap<String, Int>(1_600_000)
        val texts = ArrayList<String>(1_600_000)
        val textWeights = ArrayList<Int>(1_600_000)
        forEachEntry { entry ->
            val textId = textIds[entry.text] ?: texts.size.also {
                textIds[entry.text] = it
                texts += entry.text
                textWeights += entry.weight
            }
            if (entry.weight > textWeights[textId]) textWeights[textId] = entry.weight
            writeTemporary(0, entry.code, textId, entry.weight)
            writeTemporary(1, entry.initials, textId, entry.weight)
            writeTemporary(2, entry.t9, textId, entry.weight)
        }
        bucketStreams.values.forEach { it.close() }
        bucketStreams.clear()

        val outputFile = output.get().asFile
        outputFile.parentFile.mkdirs()
        RandomAccessFile(outputFile, "rw").use { file ->
            file.setLength(0)
            file.writeInt(0x56504934) // VPI4
            file.writeInt(5)
            file.writeLong(0L) // shared text pool
            repeat(3) { file.writeLong(0L) }
            val poolStart = file.filePointer
            file.seek(8L)
            file.writeLong(poolStart)
            file.seek(poolStart)
            file.writeInt(texts.size)
            val poolOffsets = file.filePointer
            repeat(texts.size) { file.writeLong(0L) }
            texts.forEachIndexed { textId, text ->
                val offset = file.filePointer
                file.seek(poolOffsets + textId * Long.SIZE_BYTES)
                file.writeLong(offset)
                file.seek(offset)
                file.writeInt(textWeights[textId])
                val bytes = text.toByteArray(Charsets.UTF_8)
                file.writeShort(bytes.size)
                file.write(bytes)
            }
            val selectors = listOf<(PinyinBuildEntry) -> String>({ it.code }, { it.initials }, { it.t9 })
            selectors.forEachIndexed { section, _ ->
                val orderedBuckets = if (section == 2) ('2'..'9').toList() else ('a'..'z').toList()
                val start = file.filePointer
                file.seek(16L + section * Long.SIZE_BYTES)
                file.writeLong(start)
                file.seek(start)
                // Count records from all ordered buckets before reserving offsets.
                var recordCount = 0
                orderedBuckets.forEach { bucket ->
                    val source = File(temporaryDirectory, "$section-$bucket.bin")
                    if (!source.exists()) return@forEach
                    val keys = HashSet<String>()
                    DataInputStream(BufferedInputStream(source.inputStream(), 64 * 1024)).use { input ->
                        while (true) {
                            try {
                                keys += input.readUTF()
                                input.readInt()
                                input.readInt()
                            } catch (_: java.io.EOFException) {
                                break
                            }
                        }
                    }
                    recordCount += keys.size
                }
                file.writeInt(recordCount)
                val offsetsPosition = file.filePointer
                repeat(recordCount) { file.writeLong(0L) }
                var entryIndex = 0
                orderedBuckets.forEach { bucket ->
                    val source = File(temporaryDirectory, "$section-$bucket.bin")
                    if (!source.exists()) return@forEach
                    val grouped = HashMap<String, MutableMap<Int, Int>>()
                    DataInputStream(BufferedInputStream(source.inputStream(), 64 * 1024)).use { input ->
                        while (true) {
                            try {
                                val key = input.readUTF()
                                val textId = input.readInt()
                                val weight = input.readInt()
                                val candidates = grouped.getOrPut(key) { HashMap() }
                                candidates[textId] = maxOf(candidates[textId] ?: Int.MIN_VALUE, weight)
                            } catch (_: java.io.EOFException) {
                                break
                            }
                        }
                    }
                    grouped.entries.sortedBy { it.key }.forEach { (key, candidateWeights) ->
                        val offset = file.filePointer
                        file.seek(offsetsPosition + entryIndex * Long.SIZE_BYTES)
                        file.writeLong(offset)
                        file.seek(offset)
                        val keyBytes = key.toByteArray(Charsets.UTF_8)
                        file.writeShort(keyBytes.size)
                        file.write(keyBytes)
                        val candidates = candidateWeights.entries
                            .sortedWith(compareByDescending<Map.Entry<Int, Int>> { it.value }.thenBy { texts[it.key].length })
                            .take(24)
                        file.writeByte(candidates.size)
                        candidates.forEach { candidate ->
                            file.writeInt(candidate.key)
                            file.writeInt(candidate.value)
                        }
                        entryIndex += 1
                    }
                    source.delete()
                }
            }
        }
        textIds.clear()
        essayWeights.clear()
        temporaryDirectory.deleteRecursively()
        logger.lifecycle("Generated full offline Pinyin index: ${outputFile.length() / 1024 / 1024} MiB")
    }
}

tasks.named("preBuild").configure { dependsOn(generateFullPinyinIndex) }

val prepareRuntimeAssets = tasks.register<Sync>("prepareRuntimeAssets") {
    from(layout.projectDirectory.dir("src/main/assets")) {
        // Original Rime tables are build inputs, not runtime assets. The generated
        // index above is the only Pinyin lexicon shipped to users.
        exclude("rime/**", "language/**")
    }
    into(layout.buildDirectory.dir("generated/runtimeAssets"))
}

tasks.named("preBuild").configure { dependsOn(prepareRuntimeAssets) }

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.composables:icons-lucide-android:1.1.0")
    implementation("androidx.activity:activity-ktx:1.10.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.datastore:datastore-preferences:1.1.2")
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    kapt("androidx.room:room-compiler:2.6.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    testImplementation(kotlin("test"))
}

tasks.register("verifyOpenSourceRelease") {
    group = "verification"
    description = "Checks source obligations required before publishing a GPLv3 release."
    doLast {
        val librimeLock = rootProject.file("third_party/librime/SOURCE_LOCK.md")
        val librimeSource = rootProject.file("third_party/librime/trime/app/src/main/jni/librime/src/rime_api.cc")
        val trimeJniSource = rootProject.file("third_party/librime/trime/app/src/main/jni/librime_jni/rime_jni.cc")
        check(librimeLock.isFile) {
            "Public releases require third_party/librime/SOURCE_LOCK.md and the corresponding librime source tree."
        }
        check(librimeSource.isFile && trimeJniSource.isFile) {
            "Public releases require the locked librime and Trime JNI source files."
        }
        check(rootProject.file("LICENSE").isFile) { "GPLv3 LICENSE is required." }
        check(rootProject.file("THIRD_PARTY_NOTICES.md").isFile) { "Third-party notices are required." }
    }
}
