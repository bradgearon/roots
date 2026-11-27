import java.util.Properties

plugins { id("com.android.application") }
// Match the native library's API floor, including a cached Vulkan brain.
val brainCache = rootProject.file(".roots-brain")
val brainManifest = brainCache.resolve("brain.properties")
val brainLib = brainCache.resolve("jniLibs/arm64-v8a/libroots_plamo.so")
val brainMode = providers.gradleProperty("rootsUsePrebuiltBrain").orElse("auto").get().lowercase()
val usePrebuiltBrain = brainMode == "true" || (brainMode == "auto" && (brainManifest.exists() || brainLib.exists()))
val nativeMinSdk = if (usePrebuiltBrain) {
    require(brainManifest.isFile) { "Native brain cache is incomplete. Run build-brain.cmd." }
    val manifest = Properties().apply { brainManifest.inputStream().use { load(it) } }
    val expectedApi = if (manifest.getProperty("vulkan") == "ON") 28 else 26
    require(manifest.getProperty("android_api")?.toIntOrNull() == expectedApi) { "Native brain cache is stale. Run build-brain.cmd." }
    expectedApi
} else if (providers.gradleProperty("rootsEnableVulkan").orElse("false").get().toBoolean()) 28 else 26
android {
    namespace = "com.wds.roots"
    compileSdk = 36
    ndkVersion = rootProject.extra["rootsNdkVersion"] as String
    defaultConfig { applicationId = "com.wds.roots"; minSdk = nativeMinSdk; targetSdk = 36; versionCode = 1; versionName = "0.1.0" }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
}
dependencies { implementation(project(":contextual-overlay")) }
