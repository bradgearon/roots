# Build the Android native inference brain independently of Gradle.
[CmdletBinding()]
param(
    [string]$Sdk,
    [string]$Ndk,
    [switch]$Vulkan,
    [switch]$NoKleidiAI,
    [string]$VulkanSdk = $env:VULKAN_SDK,
    [string]$SpirvHeadersDir,
    [string]$VulkanHostToolchain,
    [int]$Jobs = 0
)
$ErrorActionPreference = 'Stop'
try {
    $root = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..')).Path
    $android = Join-Path $root 'android'
    $native = Join-Path $android 'contextual-overlay\src\main\cpp'
    $provider = Join-Path $android 'contextual-overlay\src\main\java\com\wds\sixstars\contextoverlay\LocalPLaMoTranslationProvider.java'
    $cache = Join-Path $android '.roots-brain'
    $local = Join-Path $android 'local.properties'
    if (-not $Sdk -and (Test-Path -LiteralPath $local)) {
        $line = Get-Content -LiteralPath $local | Where-Object { $_ -match '^\s*sdk\.dir\s*=' } | Select-Object -First 1
        if ($line) {
            $Sdk = ($line -replace '^\s*sdk\.dir\s*=\s*','').Trim().Replace('\:',':').Replace('\\','\').Replace('\ ',' ')
            if (-not [IO.Path]::IsPathRooted($Sdk)) { $Sdk = Join-Path $android $Sdk }
        }
    }
    if (-not $Sdk) {
        foreach ($candidate in @($env:ANDROID_HOME,$env:ANDROID_SDK_ROOT,'G:\sdk','G:\sdk\Android\Sdk',(Join-Path $env:LOCALAPPDATA 'Android\Sdk'))) {
            if ($candidate -and (Test-Path -LiteralPath (Join-Path $candidate 'ndk') -PathType Container)) { $Sdk=$candidate; break }
        }
    }
    if (-not $Sdk) { throw 'Android SDK not found. Pass -Sdk G:\sdk.' }
    $Sdk = (Resolve-Path -LiteralPath $Sdk).Path
    if (-not $Ndk) { $Ndk=$env:ANDROID_NDK_HOME }
    if (-not $Ndk) {
        $installed = @(Get-ChildItem -LiteralPath (Join-Path $Sdk 'ndk') -Directory | Where-Object {
            $_.Name -match '^\d+\.\d+\.\d+$' -and
            (Test-Path -LiteralPath (Join-Path $_.FullName 'build\cmake\android.toolchain.cmake') -PathType Leaf)
        } | Sort-Object -Property @{Expression={[version]$_.Name};Descending=$true})
        if ($installed.Count -eq 0) { throw 'Install a stable side-by-side Android NDK or pass -Ndk.' }
        $Ndk=$installed[0].FullName
    }
    $Ndk=(Resolve-Path -LiteralPath $Ndk).Path
    $toolchain=Join-Path $Ndk 'build\cmake\android.toolchain.cmake'
    $cmake=Join-Path $Sdk 'cmake\3.22.1\bin\cmake.exe'
    $ninja=Join-Path $Sdk 'cmake\3.22.1\bin\ninja.exe'
    foreach ($tool in @($toolchain,$cmake,$ninja)) {
        if (-not (Test-Path -LiteralPath $tool -PathType Leaf)) { throw "Required build tool missing: $tool" }
    }
    $kai=if($NoKleidiAI){'OFF'}else{'ON'}
    $gpu=if($Vulkan){'ON'}else{'OFF'}
    # Vulkan 1.1 entry points such as vkGetPhysicalDeviceFeatures2
    # are exported by Android's loader starting at API 28.
    $api=if($Vulkan){28}else{26}
    # Keep the Vulkan build path short for KleidiAI's long kernel filenames.
    $profile=if($Vulkan){"vk$api-kai-$kai-$(Split-Path $Ndk -Leaf)"}else{"arm64-v8a-kai-$kai-vulkan-$gpu-ndk-$(Split-Path $Ndk -Leaf)"}
    $work=Join-Path $cache "build\$profile"
    $out=Join-Path $work 'lib'
    New-Item -ItemType Directory -Force -Path $out | Out-Null
    function Sha([string]$path) { return (Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash.ToLowerInvariant() }
    function JniHash {
        $declarations=@(Get-Content -LiteralPath $provider | Where-Object { $_ -match '^\s*static native\s+' } | ForEach-Object { $_.Trim() })
        if ($declarations.Count -eq 0) { throw 'JNI declarations not found.' }
        $bytes=[Text.Encoding]::UTF8.GetBytes(($declarations -join [char]10))
        $sha=[Security.Cryptography.SHA256]::Create()
        try { return ([BitConverter]::ToString($sha.ComputeHash($bytes))).Replace('-','').ToLowerInvariant() } finally { $sha.Dispose() }
    }
    $cmakeHash=Sha (Join-Path $native 'CMakeLists.txt')
    $cppHash=Sha (Join-Path $native 'roots_plamo.cpp')
    $jniHash=JniHash
    $configure=@('-S',$native,'-B',$work,'-G','Ninja',
        "-DCMAKE_MAKE_PROGRAM=$ninja","-DCMAKE_TOOLCHAIN_FILE=$toolchain",
        '-DANDROID_ABI=arm64-v8a',"-DANDROID_PLATFORM=android-$api",'-DANDROID_STL=c++_static',
        '-DCMAKE_BUILD_TYPE=Release',"-DCMAKE_LIBRARY_OUTPUT_DIRECTORY=$out",
        "-DROOTS_ENABLE_KLEIDIAI=$kai","-DROOTS_ENABLE_VULKAN=$gpu")
    if ($Vulkan) {
        if (-not $VulkanSdk) { throw 'Vulkan build needs -VulkanSdk or VULKAN_SDK.' }
        $configure+= "-DROOTS_VULKAN_SDK=$VulkanSdk"
        if ($SpirvHeadersDir) { $configure+= "-DROOTS_SPIRV_HEADERS_DIR=$SpirvHeadersDir" }
        if ($VulkanHostToolchain) { $configure+= "-DGGML_VULKAN_SHADERS_GEN_TOOLCHAIN=$VulkanHostToolchain" }
    }
    Write-Host "Building $profile (native Release, independent of Gradle)"
    Write-Host "SDK: $Sdk"
    Write-Host "NDK: $Ndk"
    Write-Host "Android native API: $api"
    & $cmake @configure
    if ($LASTEXITCODE -ne 0) { throw "CMake configure failed ($LASTEXITCODE)" }
    if ($Jobs -le 0) { $Jobs=[Math]::Max(1,[Math]::Min(8,[Environment]::ProcessorCount)) }
    & $cmake --build $work --target roots_plamo --parallel $Jobs
    if ($LASTEXITCODE -ne 0) { throw "Native build failed ($LASTEXITCODE)" }
    $lib=Join-Path $out 'libroots_plamo.so'
    if (-not (Test-Path -LiteralPath $lib -PathType Leaf)) { throw "Expected native output missing: $lib" }
    if ($cmakeHash -ne (Sha (Join-Path $native 'CMakeLists.txt')) -or $cppHash -ne (Sha (Join-Path $native 'roots_plamo.cpp')) -or $jniHash -ne (JniHash)) { throw 'Native inputs changed during build; rerun.' }
    # Publish manifest last. Gradle checks source, JNI and binary hashes.
    $stage=Join-Path $cache 'jniLibs\arm64-v8a'
    New-Item -ItemType Directory -Force -Path $stage | Out-Null
    $manifest=Join-Path $cache 'brain.properties'
    Remove-Item -LiteralPath $manifest -Force -ErrorAction SilentlyContinue
    $dest=Join-Path $stage 'libroots_plamo.so'
    Copy-Item -LiteralPath $lib -Destination "$dest.tmp" -Force
    Move-Item -LiteralPath "$dest.tmp" -Destination $dest -Force
    @("format=1","abi=arm64-v8a","android_api=$api","kleidiai=$kai","vulkan=$gpu","cmake_sha256=$cmakeHash","cpp_sha256=$cppHash","jni_sha256=$jniHash","library_sha256=$(Sha $dest)") | Set-Content -LiteralPath "$manifest.tmp" -Encoding ASCII
    Move-Item -LiteralPath "$manifest.tmp" -Destination $manifest -Force
    Write-Host "Native brain cached: $dest"
    Write-Host 'Run build-android.cmd :app:installDebug; Gradle will reuse this binary.'
    exit 0
} catch { Write-Error -Message $_.Exception.Message -ErrorAction Continue; exit 1 }
