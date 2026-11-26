plugins {
    id("com.android.library")
}
val rootsTreeEnabled = providers.gradleProperty("rootsEnableTree").orElse("false")
val rootsSqliteDir = providers.gradleProperty("rootsSqliteAmalgamationDir")
android {
    namespace = "com.wds.sixstars.contextoverlay"
    compileSdk = 36
    defaultConfig {
        minSdk = 26
        ndk { abiFilters += listOf("arm64-v8a") }
        externalNativeBuild {
            cmake {
                arguments += "-DROOTS_ENABLE_TREE=${if (rootsTreeEnabled.get().toBoolean()) "ON" else "OFF"}"
                rootsSqliteDir.orNull?.let { arguments += "-DROOTS_SQLITE_AMALGAMATION_DIR=${file(it).absolutePath}" }
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
