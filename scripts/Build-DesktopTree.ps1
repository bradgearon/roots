[CmdletBinding()]
param([switch]$Force)
$ErrorActionPreference='Stop'
$root=Split-Path -Parent $PSScriptRoot
$source=Join-Path $root 'native\tree-core'
$build=Join-Path $root 'build\desktop-tree'
$dll=Join-Path $build 'Release\roots_tree.dll'
$stamp=Join-Path $build 'source.sha256'
try {
    if ($env:ROOTS_TREE_LIBRARY) {
        if (-not [IO.Path]::IsPathRooted($env:ROOTS_TREE_LIBRARY) -or -not (Test-Path -LiteralPath $env:ROOTS_TREE_LIBRARY -PathType Leaf)) {
            throw 'ROOTS_TREE_LIBRARY must point to an existing absolute trusted library.'
        }
        Write-Host 'Roots Tree: using configured desktop library.'
        exit 0
    }
    $files=@('CMakeLists.txt','include\roots_tree.h','src\roots_tree.cpp','cmake\ProvisionSQLite.cmake')
    $inputs=($files | ForEach-Object { (Get-FileHash -LiteralPath (Join-Path $source $_) -Algorithm SHA256).Hash }) -join ':'
    if ($env:ROOTS_SQLITE_DIR) {
        foreach($name in @('sqlite3.c','sqlite3.h')) {
            $inputs += ':'+(Get-FileHash -LiteralPath (Join-Path $env:ROOTS_SQLITE_DIR $name) -Algorithm SHA256).Hash
        }
    }
    if (-not $Force -and (Test-Path $dll) -and (Test-Path $stamp) -and (Get-Content $stamp -Raw).Trim() -eq $inputs) {
        Write-Host 'Roots Tree: reusing cached desktop C++ library.'
        exit 0
    }
    $cmake=(Get-Command cmake.exe -ErrorAction SilentlyContinue | Select-Object -First 1).Source
    if (-not $cmake) {
        $vswhere=Join-Path ([Environment]::GetEnvironmentVariable('ProgramFiles(x86)')) 'Microsoft Visual Studio\Installer\vswhere.exe'
        if (Test-Path $vswhere) {
            $vs=& $vswhere -latest -products '*' -requires Microsoft.VisualStudio.Component.VC.CMake.Project -property installationPath
            if($vs){$cmake=Join-Path ($vs | Select-Object -First 1) 'Common7\IDE\CommonExtensions\Microsoft\CMake\CMake\bin\cmake.exe'}
        }
    }
    if (-not $cmake -or -not (Test-Path $cmake)) {throw 'CMake not found. Install the Visual Studio C++ CMake component or add CMake to PATH.'}
    New-Item -ItemType Directory -Path $build -Force | Out-Null
    $arguments=@('-S',$source,'-B',$build,'-G','Visual Studio 17 2022','-A','x64','-DROOTS_TREE_TESTS=OFF','-DROOTS_TREE_PROVISION_SQLITE=ON')
    if($env:ROOTS_SQLITE_DIR){$arguments+= "-DROOTS_SQLITE_AMALGAMATION_DIR=$env:ROOTS_SQLITE_DIR"}
    else{$arguments+='-DROOTS_SQLITE_AMALGAMATION_DIR='}
    & $cmake @arguments
    if($LASTEXITCODE -ne 0){throw 'Desktop Tree configure failed.'}
    & $cmake --build $build --config Release --target roots_tree --parallel
    if($LASTEXITCODE -ne 0 -or -not (Test-Path $dll)){throw 'Desktop Tree build failed.'}
    Set-Content -LiteralPath $stamp -Value $inputs -Encoding ASCII
    Write-Host "Roots Tree: $dll"
    exit 0
} catch {
    Write-Warning ('Roots desktop Tree: '+$_.Exception.Message)
    exit 1
}
