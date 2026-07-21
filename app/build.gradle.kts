import java.util.Properties

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
        versionCode = 21
        versionName = "1.4.1"
        ndk { abiFilters += "arm64-v8a" }
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
            jniLibs.srcDirs("src/main/jniLibs")
            // Only the verified runtime subset is packaged. Rime source YAML
            // dictionaries and the retired Kotlin index never ship to devices.
            assets.setSrcDirs(listOf(layout.buildDirectory.dir("generated/runtimeAssets")))
        }
    }

    externalNativeBuild { cmake { path = file("src/main/cpp/CMakeLists.txt") } }
    androidResources { noCompress += setOf("utf8", "bin") }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

val prepareRuntimeAssets = tasks.register<Sync>("prepareRuntimeAssets") {
    from(layout.projectDirectory.dir("src/main/assets")) {
        exclude("rime/**", "language/**", "pinyin/full_pinyin_index.bin")
    }
    from(layout.projectDirectory.dir("src/main/assets/rime/prebuilt")) {
        into("rime/prebuilt")
    }
    from(layout.projectDirectory.dir("src/main/assets/rime/wanxiang")) {
        into("rime/wanxiang")
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
        check(librimeLock.isFile && librimeSource.isFile && trimeJniSource.isFile) {
            "Public releases require the locked librime and Trime JNI sources."
        }
        check(rootProject.file("LICENSE").isFile) { "GPLv3 LICENSE is required." }
        check(rootProject.file("THIRD_PARTY_NOTICES.md").isFile) { "Third-party notices are required." }
    }
}
