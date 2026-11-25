plugins {
    id("com.android.library")
}
android {
    namespace = "com.wds.sixstars.contextoverlay"
    compileSdk = 36
    defaultConfig {
        minSdk = 26
        ndk { abiFilters += listOf("arm64-v8a") }
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
