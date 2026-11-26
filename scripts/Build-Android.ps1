# Windows Android launcher. No global PATH changes and no silent tool installs.
# Arguments are passed to Gradle; defaults to :app:assembleDebug.
$ErrorActionPreference = 'Stop'
$project = Join-Path $PSScriptRoot '..\android'
$project = (Resolve-Path -LiteralPath $project).Path
$tasks = @($args)
if ($tasks.Count -eq 0) { $tasks = @(':app:assembleDebug') }

try {
    # Android Studio's local.properties takes precedence over SDK environment variables.
    # Accept the common Java-properties escaped drive colon/backslashes and spaces.
    $sdk = $null
    $localProps = Join-Path $project 'local.properties'
    if (Test-Path -LiteralPath $localProps -PathType Leaf) {
        $line = Get-Content -LiteralPath $localProps | Where-Object { $_ -match '^\s*sdk\.dir\s*=' } | Select-Object -First 1
        if ($line) {
            $sdk = ($line -replace '^\s*sdk\.dir\s*=\s*','').Trim()
            $sdk = $sdk.Replace('\:', ':').Replace('\\', '\').Replace('\ ', ' ')
            if (-not [IO.Path]::IsPathRooted($sdk)) { $sdk = Join-Path $project $sdk }
            if (-not (Test-Path -LiteralPath $sdk -PathType Container)) {
                throw "Android Studio local.properties points to a missing SDK: $sdk. Correct sdk.dir or remove the stale local.properties."
            }
        }
    }
    if (-not $sdk) {
        foreach ($candidate in @($env:ANDROID_HOME, $env:ANDROID_SDK_ROOT, 'G:\sdk', 'G:\sdk\Android\Sdk', (Join-Path $env:LOCALAPPDATA 'Android\Sdk'))) {
            if ($candidate -and (Test-Path -LiteralPath (Join-Path $candidate 'platforms') -PathType Container)) {
                $sdk = $candidate
                break
            }
        }
    }
    if (-not $sdk) { throw 'Android SDK not found. Set ANDROID_HOME to its location (default search includes G:\sdk).' }
    $sdk = (Resolve-Path -LiteralPath $sdk).Path
    if (-not (Test-Path -LiteralPath (Join-Path $sdk 'platforms\android-36\android.jar') -PathType Leaf)) {
        throw "Android SDK 36 not found under $sdk. Install Android SDK Platform 36 using Android Studio SDK Manager."
    }
    $env:ANDROID_HOME = $sdk
    $env:ANDROID_SDK_ROOT = $sdk

    # Gradle needs a JDK. Prefer an explicitly configured, usable JAVA_HOME.
    $java = $null
    $studioHomes = @(
        'G:\sdk\jbr',
        'G:\sdk\Android Studio\jbr',
        'G:\sdk\studio\jbr',
        (Join-Path $env:ProgramFiles 'Android\Android Studio\jbr'),
        (Join-Path $env:LOCALAPPDATA 'Programs\Android Studio\jbr')
    )
    foreach ($candidate in (@($env:JAVA_HOME) + $studioHomes)) {
        if ($candidate -and (Test-Path -LiteralPath (Join-Path $candidate 'bin\java.exe') -PathType Leaf) -and
                           (Test-Path -LiteralPath (Join-Path $candidate 'bin\javac.exe') -PathType Leaf)) {
            $java = $candidate
            break
        }
    }
    if (-not $java) {
        $javac = Get-Command javac.exe -ErrorAction SilentlyContinue
        if ($javac) { $java = Split-Path -Parent (Split-Path -Parent $javac.Source) }
    }
    if (-not $java) { throw 'JDK not found. Set JAVA_HOME to Android Studio''s jbr directory or another compatible JDK.' }
    $env:JAVA_HOME = $java

    # Derive the required Gradle distribution from this checkout, not a hard-coded version.
    $properties = Get-Content -LiteralPath (Join-Path $project 'gradle\wrapper\gradle-wrapper.properties') -Raw
    if ($properties -notmatch '(?m)^\s*distributionUrl=.*?/gradle-(?<version>[0-9][0-9A-Za-z.\-]*)-(?<kind>bin|all)\.zip\s*$') {
        throw 'Could not read the Gradle version from gradle-wrapper.properties.'
    }
    $version = $Matches.version
    $distName = "gradle-$version"
    $gradle = $null
    $wrapper = Join-Path $project 'gradlew.bat'
    $wrapperJar = Join-Path $project 'gradle\wrapper\gradle-wrapper.jar'

    # Explicit override is useful with custom Android Studio/Gradle installations.
    if ($env:ROOTS_GRADLE_BAT) {
        if (-not (Test-Path -LiteralPath $env:ROOTS_GRADLE_BAT -PathType Leaf)) {
            throw "ROOTS_GRADLE_BAT does not exist: $env:ROOTS_GRADLE_BAT"
        }
        if ([IO.Path]::GetExtension($env:ROOTS_GRADLE_BAT) -notin @('.bat','.cmd')) {
            throw 'ROOTS_GRADLE_BAT must point to a Gradle .bat or .cmd launcher.'
        }
        $gradle = (Resolve-Path -LiteralPath $env:ROOTS_GRADLE_BAT).Path
    } elseif ((Test-Path -LiteralPath $wrapper -PathType Leaf) -and (Test-Path -LiteralPath $wrapperJar -PathType Leaf)) {
        $gradle = $wrapper
    } else {
        # Android Studio normally extracts Gradle beneath wrapper/dists, even
        # if Gradle is not globally installed and this repo lacks gradlew.bat.
        $homes = @($env:GRADLE_USER_HOME, (Join-Path $env:USERPROFILE '.gradle'), 'G:\sdk\.gradle', 'G:\sdk\gradle', 'G:\sdk')
        foreach ($homeDir in $homes) {
            if (-not $homeDir) { continue }
            foreach ($kind in @('bin','all')) {
                $distDir = Join-Path $homeDir "wrapper\dists\$distName-$kind"
                if (-not (Test-Path -LiteralPath $distDir -PathType Container)) { continue }
                foreach ($hashDir in (Get-ChildItem -LiteralPath $distDir -Directory -ErrorAction SilentlyContinue)) {
                    $candidate = Join-Path $hashDir.FullName "$distName\bin\gradle.bat"
                    if (Test-Path -LiteralPath $candidate -PathType Leaf) {
                        $gradle = $candidate
                        break
                    }
                }
                if ($gradle) { break }
            }
            if ($gradle) { break }
        }
        if (-not $gradle) {
            $directHomes = @(
                $env:GRADLE_HOME,
                "G:\sdk\$distName",
                "G:\sdk\gradle\$distName",
                "G:\sdk\tools\$distName",
                "G:\sdk\gradle",
                'G:\sdk'
            )
            foreach ($homeDir in $directHomes) {
                if (-not $homeDir) { continue }
                foreach ($name in @('gradle.bat','gradle.cmd')) {
                    $candidate = Join-Path $homeDir "bin\$name"
                    if (Test-Path -LiteralPath $candidate -PathType Leaf) {
                        $gradle = $candidate
                        break
                    }
                }
                if ($gradle) { break }
            }
        }
        if (-not $gradle) {
            $command = Get-Command gradle.bat,gradle.cmd -ErrorAction SilentlyContinue | Select-Object -First 1
            if ($command) { $gradle = $command.Source }
        }
    }
    if (-not $gradle) {
        throw "Gradle $version not found. Let Android Studio sync this checkout once, or set ROOTS_GRADLE_BAT to the full path of gradle.bat/gradle.cmd."
    }
    Write-Host "Android SDK : $sdk"
    Write-Host "JDK         : $java"
    Write-Host "Gradle      : $gradle (project requires $version)"
    Write-Host "Tasks       : $($tasks -join ' ')"
    Push-Location -LiteralPath $project
    try {
        & $gradle @tasks
        $result = $LASTEXITCODE
    } finally {
        Pop-Location
    }
    if ($result -eq 0 -and (Test-Path -LiteralPath (Join-Path $project 'app\build\outputs\apk\debug\app-debug.apk'))) {
        Write-Host "Debug APK: $(Join-Path $project 'app\build\outputs\apk\debug\app-debug.apk')"
    }
    exit $result
} catch {
    Write-Error -Message $_.Exception.Message -ErrorAction Continue
    exit 1
}
