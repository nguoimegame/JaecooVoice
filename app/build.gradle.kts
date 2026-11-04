import java.net.URI
import java.security.MessageDigest
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
}

// Version comes from the release tag (see .github/workflows/voice-release.yml):
// ./gradlew assembleRelease -PvoiceVersionName=1.2.3 -PvoiceVersionCode=10203
val voiceVersionName = (findProperty("voiceVersionName") as String?)?.trim().orEmpty().ifEmpty { "0.0.1" }
val voiceVersionCode = (findProperty("voiceVersionCode") as String?)?.trim()?.toIntOrNull() ?: 1

// JaecooApp's signing key is never committed here (this repository is public). It is looked up in:
// -PjaecooKeystore / env JAECOO_KEYSTORE / local.properties jaecoo.keystore, then the sibling
// checkout ../JaecooApp/app/debug.keystore. CI decodes it from the secret JAECOO_KEYSTORE_B64.
// Without it, debug builds use the default Android debug key (JaecooApp will refuse to bind)
// and release builds stay unsigned (the release workflow rejects them).
val localProps = Properties().apply {
    rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}
val jaecooKeystore: File? = listOfNotNull(
    findProperty("jaecooKeystore") as String?,
    System.getenv("JAECOO_KEYSTORE"),
    localProps.getProperty("jaecoo.keystore")
).firstOrNull { it.isNotBlank() }
    ?.let { file(it) }
    ?: rootProject.file("../JaecooApp/app/debug.keystore").takeIf { it.exists() }

val sherpaOnnxVersion = "2.0.1"
val sherpaOnnxAar = "sherpa-onnx-$sherpaOnnxVersion.aar"
val sherpaOnnxSha256 = "a8d09d1d32f3eb8520482c6d3d43fae674681c5b050385967c70a3c2c9bf0ee7"

// Downloads the sherpa-onnx AAR when missing (49 MB, not committed) and checks its sha256.
val downloadSherpaOnnx by tasks.registering {
    val version = sherpaOnnxVersion
    val aarName = sherpaOnnxAar
    val expectedSha = sherpaOnnxSha256
    val target = layout.projectDirectory.file("libs/$sherpaOnnxAar").asFile
    outputs.file(target)
    doLast {
        fun sha256(file: File) = MessageDigest.getInstance("SHA-256")
            .digest(file.readBytes()).joinToString("") { "%02x".format(it) }
        if (!target.exists() || sha256(target) != expectedSha) {
            target.parentFile.mkdirs()
            val url = "https://github.com/k2-fsa/sherpa-onnx/releases/download/v$version/$aarName"
            URI(url).toURL().openStream().use { input -> target.outputStream().use { input.copyTo(it) } }
            check(sha256(target) == expectedSha) { "sha256 of $aarName does not match" }
        }
    }
}

tasks.named("preBuild") {
    dependsOn(downloadSherpaOnnx)
}

android {
    namespace = "com.example.jaecoovoice"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.example.jaecoovoice"
        minSdk = 29
        targetSdk = 37
        versionCode = voiceVersionCode
        versionName = voiceVersionName

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // sherpa-onnx carries libonnxruntime (~25 MB per ABI). The head unit is arm64; debug adds
        // x86_64 for the emulator (see buildTypes).
        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    // libjaecoo-espeak.so: espeak-ng phonemizer for Piper TTS (src/main/cpp, GPL-3.0). Built for
    // every ABI in abiFilters. espeak-ng is a git submodule: clone with --recurse-submodules.
    ndkVersion = "27.0.12077973"
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    buildFeatures {
        // The contract with JaecooApp (src/main/aidl, docs/jaecoovoice-contract.md).
        aidl = true
    }

    // Same locales as JaecooApp.
    androidResources {
        localeFilters += listOf("en", "vi", "id", "in", "th", "pt", "kk", "zh", "fr", "ar", "ru", "es")
    }

    // JaecooApp's key: the signature permission of the contract and JaecooApp's check before it
    // installs this APK both depend on it. Never change it.
    signingConfigs {
        if (jaecooKeystore != null) {
            create("jaecoo") {
                storeFile = jaecooKeystore
                storePassword = "android"
                keyAlias = "androiddebugkey"
                keyPassword = "android"
            }
        }
    }

    buildTypes {
        debug {
            signingConfigs.findByName("jaecoo")?.let { signingConfig = it }
            ndk {
                abiFilters += "x86_64"
            }
        }
        release {
            signingConfig = signingConfigs.findByName("jaecoo")
            optimization {
                enable = true
            }
        }
    }
    // Shipped through japp.cloud, so download size wins over install size.
    packaging {
        dex {
            useLegacyPackaging = true
        }
        jniLibs {
            useLegacyPackaging = true
        }
    }
    // Engine code logs through android.util.Log; in JVM unit tests that is a stub, so let it be a no-op.
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    // Offline speech recognition (Apache-2.0). Not on Maven; downloadSherpaOnnx fetches it.
    implementation(files("libs/$sherpaOnnxAar"))
    // Unpacks downloaded models (.tar.bz2). Apache-2.0.
    implementation(libs.commons.compress)
    testImplementation(libs.junit)
    // android.jar only stubs org.json in unit tests.
    testImplementation(libs.org.json)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.rules)
}

// `./gradlew check` also runs the static analysis set up in the root build file.
tasks.named("check") {
    dependsOn(":ktlintCheck", ":detekt")
}
