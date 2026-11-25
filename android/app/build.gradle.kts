plugins { id("com.android.application") }
android {
    namespace = "com.wds.roots"
    compileSdk = 36
    defaultConfig { applicationId = "com.wds.roots"; minSdk = 26; targetSdk = 36; versionCode = 1; versionName = "0.1.0" }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
}
dependencies { implementation(project(":contextual-overlay")) }
