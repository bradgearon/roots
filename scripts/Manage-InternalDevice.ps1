param([switch]$Remove,[switch]$List,[string]$Iroh,[string]$Libp2p)
$ErrorActionPreference='Stop'
$dir=Join-Path $env:LOCALAPPDATA 'Roots\intelligence\internal-mesh'
$path=Join-Path $dir 'allowed-devices.txt'
New-Item -ItemType Directory -Force -Path $dir | Out-Null
$rows=@()
if(Test-Path -LiteralPath $path){$rows=@(Get-Content -LiteralPath $path | Where-Object { $_ -match '^(iroh [0-9a-f]{64}|libp2p [A-Za-z0-9]{20,120})$' })}
if($List){$rows | Write-Host;exit 0}
if(-not $Iroh -and -not $Libp2p){
    Write-Host 'Paste the two public identity lines from the phone (or press Enter to skip either transport).'
    $Iroh=(Read-Host 'iroh identity') -replace '^iroh\s+',''
    $Libp2p=(Read-Host 'libp2p identity') -replace '^libp2p\s+',''
}
$changes=@()
if($Iroh){if($Iroh -notmatch '^[0-9a-fA-F]{64}$'){throw 'Invalid Iroh endpoint ID'};$changes+="iroh "+$Iroh.ToLowerInvariant()}
if($Libp2p){if($Libp2p -cnotmatch '^[A-Za-z0-9]{20,120}$'){throw 'Invalid libp2p peer ID'};$changes+="libp2p "+$Libp2p}
if(-not $changes.Count){throw 'No device identity supplied'}
if($Remove){$rows=@($rows|Where-Object{$_ -cnotin $changes})}else{$rows=@(($rows+$changes)|Sort-Object -CaseSensitive -Unique)}
$temp=$path+'.new'
[IO.File]::WriteAllLines($temp,[string[]]$rows,[Text.UTF8Encoding]::new($false))
if(Test-Path -LiteralPath $path){[IO.File]::Replace($temp,$path,$null)}else{[IO.File]::Move($temp,$path)}
if($Remove){Write-Host 'Removed device transport permissions. New sessions are denied; existing sessions may finish within their deadline.'}
else{Write-Host 'Device transport permission saved. Pair the separate desktop inference key on the phone as well.'}
