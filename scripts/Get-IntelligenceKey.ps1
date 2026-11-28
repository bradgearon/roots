$ErrorActionPreference = 'Stop'
try {
    $directory = Join-Path $env:LOCALAPPDATA 'Roots\intelligence'
    $keyFile = Join-Path $directory 'intelligence-token.txt'
    $pinFile = Join-Path $directory 'compute-tls-sha256.txt'
    if (-not (Test-Path -LiteralPath $keyFile -PathType Leaf) -or -not (Test-Path -LiteralPath $pinFile -PathType Leaf)) {
        throw 'Start launch-intelligence.cmd first to create its separate TLS identity and access key.'
    }
    $key = (Get-Content -LiteralPath $keyFile -Raw).Trim()
    $pin = (Get-Content -LiteralPath $pinFile -Raw).Trim()
    if ($key -notmatch '^[0-9a-fA-F]{64}$' -or $pin -notmatch '^[0-9a-fA-F]{64}$') {
        throw 'Invalid Roots intelligence pairing key.'
    }
    $value = $key + ':' + $pin
    Set-Clipboard -Value $value
    Write-Host $value
    Write-Host 'Intelligence pairing key copied. Paste into Desktop intelligence settings for port 8221. Keep private.'
    exit 0
} catch {
    Write-Error -Message $_.Exception.Message -ErrorAction Continue
    exit 1
}
