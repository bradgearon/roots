import java.security.MessageDigest
import java.util.Properties

plugins {
    id("com.android.library")
}
val rootsTreeEnabled = providers.gradleProperty("rootsEnableTree").orElse("true")
val rootsSqliteDir = providers.gradleProperty("rootsSqliteAmalgamationDir")
    .orElse(providers.environmentVariable("ROOTS_SQLITE_DIR"))
val rootsKleidiAI = providers.gradleProperty("rootsEnableKleidiAI").orElse("true")
val rootsVulkan = providers.gradleProperty("rootsEnableVulkan").orElse("false")
val rootsVulkanSdk = providers.gradleProperty("rootsVulkanSdk")
val rootsSpirvHeaders = providers.gradleProperty("rootsSpirvHeadersDir")
val rootsVulkanHostToolchain = providers.gradleProperty("rootsVulkanHostToolchain")
// Default to the independently compiled brain when present. Fail closed on
// stale/incomplete artifacts rather than silently reusing incompatible JNI.
val brainCache = rootProject.file(".roots-brain")
val brainManifest = brainCache.resolve("brain.properties")
val brainJniDir = brainCache.resolve("jniLibs")
val brainLib = brainJniDir.resolve("arm64-v8a/libroots_plamo.so")
val brainMode = providers.gradleProperty("rootsUsePrebuiltBrain").orElse("auto").get().lowercase()
require(brainMode in setOf("auto", "true", "false")) { "rootsUsePrebuiltBrain must be auto, true or false" }
val usePrebuiltBrain = brainMode == "true" || (brainMode == "auto" && (brainManifest.exists() || brainLib.exists()))
fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
var nativeMinSdk = if (rootsVulkan.get().toBoolean()) 28 else 26
if (usePrebuiltBrain) {
    val invalid = "Native brain cache is missing/stale. Run build-brain.cmd or use -ProotsUsePrebuiltBrain=false."
    require(brainManifest.isFile && brainLib.isFile) { invalid }
    val manifest = Properties().apply { brainManifest.inputStream().use { load(it) } }
    val source = file("src/main/cpp")
    val provider = file("src/main/java/com/wds/sixstars/contextoverlay/LocalPLaMoTranslationProvider.java")
    val signatures = provider.readLines().map { it.trim() }.filter { it.startsWith("static native ") }.joinToString("\n")
    require(manifest.getProperty("format") == "1" && manifest.getProperty("abi") == "arm64-v8a" &&
        manifest.getProperty("cmake_sha256") == sha256(source.resolve("CMakeLists.txt").readBytes()) &&
        manifest.getProperty("cpp_sha256") == sha256(source.resolve("roots_plamo.cpp").readBytes()) &&
        manifest.getProperty("jni_sha256") == sha256(signatures.toByteArray(Charsets.UTF_8)) &&
        manifest.getProperty("library_sha256") == sha256(brainLib.readBytes())) { invalid }
    require(manifest.getProperty("ndk_version") == (rootProject.extra["rootsNdkVersion"] as String)) {
        "Cached brain NDK differs from selected NDK (or its version is unknown). Run build-brain.cmd again."
    }
    val expectedApi = if (manifest.getProperty("vulkan") == "ON") 28 else 26
    require(manifest.getProperty("android_api")?.toIntOrNull() == expectedApi) { invalid }
    nativeMinSdk = expectedApi
    listOf("rootsEnableKleidiAI" to "kleidiai", "rootsEnableVulkan" to "vulkan").forEach { (property, key) ->
        providers.gradleProperty(property).orNull?.let { value ->
            require(manifest.getProperty(key) == if (value.toBoolean()) "ON" else "OFF") {
                "Cached brain profile differs from $property. Rebuild with build-brain.cmd or use -ProotsUsePrebuiltBrain=false."
            }
        }
    }
    logger.lifecycle("Roots: reusing cached ARM64 brain (${manifest.getProperty("kleidiai")} KleidiAI, ${manifest.getProperty("vulkan")} Vulkan)")
}
logger.lifecycle("Roots: native Tree ${if (rootsTreeEnabled.get().toBoolean()) "enabled" else "disabled"}")
android {
    namespace = "com.wds.sixstars.contextoverlay"
    compileSdk = 36
    ndkVersion = rootProject.extra["rootsNdkVersion"] as String
    defaultConfig {
        minSdk = nativeMinSdk
        ndk { abiFilters += listOf("arm64-v8a") }
        if (!usePrebuiltBrain || rootsTreeEnabled.get().toBoolean()) externalNativeBuild {
            cmake {
                arguments += "-DROOTS_ENABLE_KLEIDIAI=${if (rootsKleidiAI.get().toBoolean()) "ON" else "OFF"}"
                arguments += "-DROOTS_ENABLE_VULKAN=${if (rootsVulkan.get().toBoolean()) "ON" else "OFF"}"
                rootsVulkanSdk.orNull?.let { arguments += "-DROOTS_VULKAN_SDK=${file(it).absolutePath}" }
                rootsSpirvHeaders.orNull?.let { arguments += "-DROOTS_SPIRV_HEADERS_DIR=${file(it).absolutePath}" }
                rootsVulkanHostToolchain.orNull?.let { arguments += "-DGGML_VULKAN_SHADERS_GEN_TOOLCHAIN=${file(it).absolutePath}" }
                arguments += "-DROOTS_ENABLE_TREE=${if (rootsTreeEnabled.get().toBoolean()) "ON" else "OFF"}"
                arguments += "-DROOTS_TREE_TESTS=OFF"
                rootsSqliteDir.orNull?.let { arguments += "-DROOTS_SQLITE_AMALGAMATION_DIR=${file(it).absolutePath}" }
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    // Optional independently built Rust transport; does not invalidate the brain cache.
    sourceSets { named("main").configure { jniLibs.srcDir(rootProject.file(".roots-whirlwind/jniLibs")) } }
    if (usePrebuiltBrain) {
        // AGP 9.2+ getter exposes a legacy source-set type (Google issue 550220873).
        sourceSets {
            named("main").configure {
                jniLibs.srcDir(brainJniDir)
            }
        }
        if (rootsTreeEnabled.get().toBoolean()) {
            externalNativeBuild { cmake { path = file("../../native/tree-core/CMakeLists.txt"); version = "3.22.1" } }
        }
    } else {
        externalNativeBuild { cmake { path = file("src/main/cpp/CMakeLists.txt"); version = "3.22.1" } }
    }
    lint {
        abortOnError = true
    }
}
dependencies {
    implementation("com.squareup.okhttp3:okhttp:5.4.0")
    implementation("com.samskivert:jmustache:1.16")
    // Small bundled language classifier; no Roots brain/llama.cpp rebuild.
    implementation("com.google.mlkit:language-id:17.0.6")
    testImplementation("junit:junit:4.13.2")
}
