param([ValidateSet('desktop','android','all')][string]$Target='all')
$ErrorActionPreference='Stop'
$root=(Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$crate=Join-Path $root 'native\whirlwind'
if(-not (Get-Command cargo -ErrorAction SilentlyContinue)){throw 'Install Rust via rustup (stable MSVC) before building Whirlwind.'}
Push-Location $crate
try{
    if($Target -in @('desktop','all')){
        & cargo build --release --bin roots-whirlwind
        if($LASTEXITCODE -ne 0){throw 'Whirlwind desktop build failed'}
    }
    if($Target -in @('android','all')){
        if(-not (Get-Command cargo-ndk -ErrorAction SilentlyContinue)){throw 'Install cargo-ndk explicitly with cargo install cargo-ndk, then rerun.'}
        $ndk=$env:ANDROID_NDK_HOME
        if(-not $ndk){
            $sdk=$env:ANDROID_HOME
            if(-not $sdk){$sdk=$env:ANDROID_SDK_ROOT}
            if(-not $sdk){
                $local=Join-Path $root 'android\local.properties'
                if(Test-Path $local){
                    $line=Get-Content $local|Where-Object{$_ -match '^\s*sdk\.dir\s*='}|Select-Object -First 1
                    if($line){$sdk=($line -replace '^\s*sdk\.dir\s*=','').Trim().Replace('\:',':').Replace('\\','\')}
                }
            }
            if(-not $sdk){$sdk='G:\sdk'}
            $ndkRoot=Join-Path $sdk 'ndk'
            if(Test-Path $ndkRoot){$ndk=(Get-ChildItem $ndkRoot -Directory|Sort-Object Name -Descending|Select-Object -First 1).FullName}
        }
        if(-not $ndk -or -not(Test-Path (Join-Path $ndk 'source.properties'))){throw 'Set ANDROID_NDK_HOME to an installed Android NDK.'}
        $env:ANDROID_NDK_HOME=$ndk
        $out=Join-Path $root 'android\.roots-whirlwind\jniLibs'
        & cargo ndk -t arm64-v8a -p 26 -o $out build --release --lib
        if($LASTEXITCODE -ne 0){throw 'Whirlwind Android build failed'}
        Write-Host "Android native library: $out"
    }
}finally{Pop-Location}
