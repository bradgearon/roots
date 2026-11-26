plugins {
    id("com.android.library")
}
val rootsKleidiAI = providers.gradleProperty("rootsEnableKleidiAI").orElse("true")
val rootsVulkan = providers.gradleProperty("rootsEnableVulkan").orElse("false")
val rootsVulkanSdk = providers.gradleProperty("rootsVulkanSdk")
val rootsSpirvHeaders = providers.gradleProperty("rootsSpirvHeadersDir")
val rootsVulkanHostToolchain = providers.gradleProperty("rootsVulkanHostToolchain")
android {
    namespace = "com.wds.sixstars.contextoverlay"
    compileSdk = 36
    defaultConfig {
        minSdk = 26
        ndk { abiFilters += listOf("arm64-v8a") }
        externalNativeBuild {
            cmake {
                arguments += "-DROOTS_ENABLE_KLEIDIAI=${if (rootsKleidiAI.get().toBoolean()) "ON" else "OFF"}"
                arguments += "-DROOTS_ENABLE_VULKAN=${if (rootsVulkan.get().toBoolean()) "ON" else "OFF"}"
                rootsVulkanSdk.orNull?.let { arguments += "-DROOTS_VULKAN_SDK=${file(it).absolutePath}" }
                rootsSpirvHeaders.orNull?.let { arguments += "-DROOTS_SPIRV_HEADERS_DIR=${file(it).absolutePath}" }
                rootsVulkanHostToolchain.orNull?.let { arguments += "-DGGML_VULKAN_SHADERS_GEN_TOOLCHAIN=${file(it).absolutePath}" }
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    externalNativeBuild { cmake { path = file("src/main/cpp/CMakeLists.txt"); version = "3.22.1" } }
    lint {
        abortOnError = true
    }
}
dependencies {
    implementation("com.squareup.okhttp3:okhttp:5.4.0")
    testImplementation("junit:junit:4.13.2")
}
