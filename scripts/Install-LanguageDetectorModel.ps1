param([string]$Destination = (Join-Path $env:LOCALAPPDATA 'Roots\models\lid.176.ftz'))
$ErrorActionPreference = 'Stop'
if (Test-Path -LiteralPath $Destination) { Write-Host "Model already installed: $Destination"; return }
$dir = Split-Path -Parent $Destination
if ($dir) { New-Item -ItemType Directory -Force -Path $dir | Out-Null }
$tmp = "$Destination.$PID.tmp"
try {
    Invoke-WebRequest -Uri 'https://dl.fbaipublicfiles.com/fasttext/supervised-models/lid.176.ftz' -OutFile $tmp
    if ((Get-Item -LiteralPath $tmp).Length -lt 900000) { throw 'Unexpected model length; verify the official model before installing.' }
    Move-Item -LiteralPath $tmp -Destination $Destination
    Write-Host "Installed fastText language model: $Destination"
} finally {
    if (Test-Path -LiteralPath $tmp) { Remove-Item -LiteralPath $tmp -Force }
}
