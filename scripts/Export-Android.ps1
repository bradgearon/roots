# Export the app's local knowledge, not downloaded inference weights.
[CmdletBinding()]
param(
    [string]$Serial,
    [string]$OutputDir,
    [string]$Adb,
    [switch]$LogsOnly,
    [string]$SevenZip,
    [switch]$NoZip
)
$ErrorActionPreference = 'Stop'
$package = 'com.wds.roots'
$repo = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..')).Path

function Find-Adb {
    if ($Adb) { return (Resolve-Path -LiteralPath $Adb).Path }
    $roots = @()
    $props = Join-Path $repo 'android\local.properties'
    if (Test-Path -LiteralPath $props) {
        $line = Get-Content -LiteralPath $props | Where-Object { $_ -match '^\s*sdk\.dir\s*=' } | Select-Object -First 1
        if ($line) {
            $path = ($line -replace '^\s*sdk\.dir\s*=\s*','').Trim().Replace('\:',':').Replace('\\','\').Replace('\ ',' ')
            if (-not [IO.Path]::IsPathRooted($path)) { $path = Join-Path (Join-Path $repo 'android') $path }
            $roots += $path
        }
    }
    $roots += @($env:ANDROID_HOME,$env:ANDROID_SDK_ROOT,'G:\sdk','G:\sdk\Android\Sdk',(Join-Path $env:LOCALAPPDATA 'Android\Sdk'))
    foreach ($root in $roots) {
        if (-not $root) { continue }
        $candidate = Join-Path $root 'platform-tools\adb.exe'
        if (Test-Path -LiteralPath $candidate -PathType Leaf) { return (Resolve-Path -LiteralPath $candidate).Path }
    }
    $command = Get-Command adb.exe -ErrorAction SilentlyContinue
    if ($command) { return $command.Source }
    throw 'ADB not found. Install Android SDK Platform-Tools or pass -Adb <path>.'
}

# All remote arguments are fixed paths. The serial is validated before using
# ProcessStartInfo.Arguments, which is needed for binary-safe PS 5.1 export.
function Copy-PrivateFile([string]$Remote,[string]$Local,[bool]$Optional) {
    & $adbExe -s $Serial shell run-as $package test -f $Remote
    if ($LASTEXITCODE -ne 0) {
        if ($Optional) { Write-Host "Not present: $Remote"; return $false }
        throw "Required file missing: $Remote. Enable local capture and open the Tree first."
    }
    $sizeText = (& $adbExe -s $Serial shell run-as $package stat -c %s $Remote | Out-String).Trim()
    if ($LASTEXITCODE -ne 0 -or $sizeText -notmatch '^\d+$') { throw "Cannot determine size: $Remote" }
    $expected = [long]$sizeText
    $partial = "$Local.partial"
    $process = New-Object System.Diagnostics.Process
    $process.StartInfo.FileName = $adbExe
    $process.StartInfo.Arguments = "-s $Serial exec-out run-as $package cat $Remote"
    $process.StartInfo.UseShellExecute = $false
    $process.StartInfo.RedirectStandardOutput = $true
    $process.StartInfo.RedirectStandardError = $true
    $process.StartInfo.CreateNoWindow = $true
    try {
        if (-not $process.Start()) { throw 'Could not start adb.' }
        $stderr = $process.StandardError.ReadToEndAsync()
        $stream = [IO.File]::Open($partial,[IO.FileMode]::Create,[IO.FileAccess]::Write,[IO.FileShare]::None)
        try { $process.StandardOutput.BaseStream.CopyTo($stream) } finally { $stream.Dispose() }
        $process.WaitForExit()
        $errorText = $stderr.GetAwaiter().GetResult()
        if ($process.ExitCode -ne 0) { throw "ADB export failed: $Remote ($errorText)" }
        $actual = (Get-Item -LiteralPath $partial).Length
        if ($actual -ne $expected) {
            if ($LogsOnly) { Write-Warning "Log changed during live export: $Remote ($expected -> $actual bytes)." }
            else { throw "Unexpected file length for $Remote ($expected expected, $actual copied)." }
        }
        Move-Item -LiteralPath $partial -Destination $Local
        Write-Host "Exported: $Remote ($actual bytes)"
        return $true
    } finally {
        if (Test-Path -LiteralPath $partial) { Remove-Item -LiteralPath $partial -Force }
        $process.Dispose()
    }
}

try {
    $adbExe = Find-Adb
    $devices = @(& $adbExe devices)
    if ($LASTEXITCODE -ne 0) { throw 'adb devices failed.' }
    $connected = @($devices | ForEach-Object { if ($_ -match '^(\S+)\s+device\s*$') { $Matches[1] } })
    if (-not $Serial) {
        if ($connected.Count -ne 1) { throw "Connect exactly one authorized device or pass -Serial. Run adb devices to check." }
        $Serial = $connected[0]
    }
    if ($Serial -notmatch '^[A-Za-z0-9_.:\-\[\]]+$' -or $Serial -notin $connected) {
        throw 'The selected ADB serial is not an authorized connected device.'
    }
    & $adbExe -s $Serial shell run-as $package id
    if ($LASTEXITCODE -ne 0) { throw 'Cannot access Roots private files. Install the debuggable APK and authorize USB debugging.' }

    if (-not $OutputDir) { $OutputDir = Join-Path $repo 'logs\device-exports' }
    $OutputDir = [IO.Path]::GetFullPath($OutputDir)
    $stamp = Get-Date -Format 'yyyyMMdd-HHmmss-fff'
    $dest = Join-Path $OutputDir ($stamp + '-' + [Guid]::NewGuid().ToString('N').Substring(0,8))
    $tree = Join-Path $dest 'tree'
    $logs = Join-Path $dest 'diagnostics'
    New-Item -ItemType Directory -Path $dest,$logs -Force | Out-Null
    Write-Host "ADB    : $adbExe"
    Write-Host "Device : $Serial"
    Write-Host "Export : $dest"

    if (-not $LogsOnly) {
        # Stop the app before copying the database + WAL as a coherent set.
        # No files are removed, no app data is cleared. Pending memory-only
        # acquisition can be lost; pause capture in Roots before exporting.
        Write-Host 'Stopping Roots to obtain a stable SQLite snapshot. Reopen Roots afterward.'
        & $adbExe -s $Serial shell am force-stop $package
        if ($LASTEXITCODE -ne 0) { throw 'Could not stop Roots; refusing to copy a live SQLite database.' }
        New-Item -ItemType Directory -Path $tree -Force | Out-Null
        $db = Copy-PrivateFile 'no_backup/roots-tree/tree.db' (Join-Path $tree 'tree.db') $true
        if ($db) {
            foreach ($name in @('tree.db-wal','tree.db-shm','tree.db-journal')) {
                [void](Copy-PrivateFile "no_backup/roots-tree/$name" (Join-Path $tree $name) $true)
            }
        } else { Write-Warning 'No Tree database yet. Enable capture in Roots and browse the X Home timeline.' }
        [void](Copy-PrivateFile 'no_backup/roots-tree/tree.gguf' (Join-Path $tree 'tree.gguf') $true)
    }
    foreach ($name in @('plamo-lifecycle.jsonl','plamo-lifecycle.previous.jsonl','functiongemma-lifecycle.jsonl','functiongemma-lifecycle.previous.jsonl','tree-lifecycle.jsonl','tree-lifecycle.previous.jsonl')) {
        [void](Copy-PrivateFile "files/diagnostics/$name" (Join-Path $logs $name) $true)
    }
    $entries = @(Get-ChildItem -LiteralPath $dest -File -Recurse | ForEach-Object {
        [ordered]@{
            path = $_.FullName.Substring($dest.Length + 1).Replace('\','/')
            bytes = $_.Length
            sha256 = (Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash.ToLowerInvariant()
        }
    })
    [ordered]@{ exportedUtc = [DateTime]::UtcNow.ToString('o'); package = $package; logsOnly = [bool]$LogsOnly; files = $entries } |
        ConvertTo-Json -Depth 5 | Set-Content -LiteralPath (Join-Path $dest 'manifest.json') -Encoding UTF8
    if (-not $LogsOnly) { Write-Host 'Reopen Roots. Check that its accessibility service is still enabled.' }
    if (-not $NoZip) {
        $zip = "$dest.zip"
        $zipExe = $null
        if ($SevenZip) { $zipExe = (Resolve-Path -LiteralPath $SevenZip).Path }
        else {
            $found = Get-Command 7z.exe,7za.exe -ErrorAction SilentlyContinue | Select-Object -First 1
            if ($found) { $zipExe = $found.Source }
            else {
                foreach ($root in @($env:ProgramFiles,${env:ProgramFiles(x86)},$env:LOCALAPPDATA)) {
                    if (-not $root) { continue }
                    $candidate = Join-Path $root '7-Zip\7z.exe'
                    if (Test-Path -LiteralPath $candidate -PathType Leaf) { $zipExe = $candidate; break }
                }
            }
        }
        try {
            if ($zipExe) {
                Push-Location -LiteralPath $OutputDir
                try {
                    & $zipExe a -tzip -mx=5 $zip (Split-Path -Leaf $dest)
                    if ($LASTEXITCODE -ne 0) { throw '7-Zip archive creation failed.' }
                    & $zipExe t $zip
                    if ($LASTEXITCODE -ne 0) { throw '7-Zip archive verification failed.' }
                } finally { Pop-Location }
            } else {
                Add-Type -AssemblyName System.IO.Compression.FileSystem
                [IO.Compression.ZipFile]::CreateFromDirectory($dest,$zip,[IO.Compression.CompressionLevel]::Optimal,$true)
                $check = [IO.Compression.ZipFile]::OpenRead($zip)
                try { if ($check.Entries.Count -eq 0) { throw 'Archive has no entries.' } } finally { $check.Dispose() }
            }
            Write-Host "ZIP archive: $zip"
        } catch {
            if (Test-Path -LiteralPath $zip) { Remove-Item -LiteralPath $zip -Force }
            throw
        }
    }
    Write-Host "Export complete: $dest"
    if ($entries.Count -eq 0) { Write-Warning 'No requested files were present.' }
    exit 0
} catch {
    Write-Error -Message $_.Exception.Message -ErrorAction Continue
    exit 1
}
