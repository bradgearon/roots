import java.util.Properties
import java.io.File

plugins {
    id("com.android.application") version "9.2.1" apply false
    id("com.android.library") version "9.2.1" apply false
}


// Choose the newest complete stable side-by-side NDK, matching Build-Brain.ps1.
val local = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.isFile) f.inputStream().use { load(it) }
}
val configuredSdk = local.getProperty("sdk.dir")
val sdk = if (!configuredSdk.isNullOrBlank()) {
    rootProject.file(configuredSdk).also { require(it.isDirectory) { "Invalid sdk.dir: $it" } }
} else {
    listOfNotNull(System.getenv("ANDROID_HOME"), System.getenv("ANDROID_SDK_ROOT"),
        "G:\\sdk", "G:\\sdk\\Android\\Sdk",
        System.getenv("LOCALAPPDATA")?.let { "$it/Android/Sdk" },
        System.getProperty("user.home") + "/Android/Sdk",
        System.getProperty("user.home") + "/Library/Android/sdk"
    ).map { File(it) }.firstOrNull { it.resolve("platforms").isDirectory }
        ?: error("Android SDK not found. Configure sdk.dir or ANDROID_HOME.")
}
val stableVersion = Regex("""\d+\.\d+\.\d+""")
fun validNdk(dir: File): Boolean {
    if (!dir.isDirectory || !stableVersion.matches(dir.name) ||
        !dir.resolve("build/cmake/android.toolchain.cmake").isFile) return false
    return runCatching {
        val p = Properties().apply { dir.resolve("source.properties").inputStream().use { load(it) } }
        p.getProperty("Pkg.Revision")?.trim() == dir.name
    }.getOrDefault(false)
}
val overrideNdk = providers.environmentVariable("ROOTS_NDK_VERSION").orNull?.trim()?.takeIf { it.isNotEmpty() }
val ndkRoot = sdk.resolve("ndk")
val selectedNdk = if (overrideNdk != null) {
    require(stableVersion.matches(overrideNdk)) { "ROOTS_NDK_VERSION must be major.minor.build" }
    ndkRoot.resolve(overrideNdk).also { require(validNdk(it)) { "Requested stable NDK is not installed: $it" } }
} else {
    ndkRoot.listFiles()?.filter { validNdk(it) }?.maxWithOrNull(
        compareBy<File> { it.name.split('.')[0].toInt() }
            .thenBy { it.name.split('.')[1].toInt() }
            .thenBy { it.name.split('.')[2].toInt() }
    ) ?: error("Install a complete stable side-by-side NDK under $ndkRoot.")
}
extra["rootsNdkVersion"] = selectedNdk.name
logger.lifecycle("Roots NDK: "+selectedNdk.name+" ("+selectedNdk.absolutePath+")")
