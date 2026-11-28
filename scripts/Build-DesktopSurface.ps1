[CmdletBinding()]
param([switch]$NoOcr)
$ErrorActionPreference='Stop'
$root=Split-Path -Parent $PSScriptRoot
$cmake=(Get-Command cmake.exe -ErrorAction SilentlyContinue | Select-Object -First 1).Source
if (-not $cmake) {
    $vswhere=Join-Path ([Environment]::GetEnvironmentVariable('ProgramFiles(x86)')) 'Microsoft Visual Studio\Installer\vswhere.exe'
    if(Test-Path $vswhere) {
        $vs=& $vswhere -latest -products '*' -requires Microsoft.VisualStudio.Component.VC.CMake.Project -property installationPath
        if($vs){$cmake=Join-Path ($vs | Select-Object -First 1) 'Common7\IDE\CommonExtensions\Microsoft\CMake\CMake\bin\cmake.exe'}
    }
}
if(-not $cmake -or -not (Test-Path $cmake)){throw 'Install Visual Studio C++ CMake tools and the Windows SDK.'}
$source=Join-Path $root 'native\desktop-surface'
$build=Join-Path $root 'build\desktop-surface'
$ocr=if($NoOcr){'OFF'}else{'ON'}
& $cmake -S $source -B $build -G 'Visual Studio 17 2022' -A x64 "-DROOTS_SURFACE_OCR=$ocr"
if($LASTEXITCODE -ne 0){throw 'Surface configure failed.'}
& $cmake --build $build --config Release --parallel
if($LASTEXITCODE -ne 0){throw 'Surface build failed.'}
& powershell -NoProfile -ExecutionPolicy Bypass -File (Join-Path $PSScriptRoot 'Build-DesktopTree.ps1')
if($LASTEXITCODE -ne 0){throw 'Private Tree library build failed.'}
& dotnet build (Join-Path $root 'src\Wds.Roots.Experience') -c Release
if($LASTEXITCODE -ne 0){throw 'Experience receiver build failed.'}
Write-Host 'Surface build complete. Use launch-surface.cmd to watch the foreground desktop.'
