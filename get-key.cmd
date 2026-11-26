@echo off
setlocal
set "KEYFILE=%LOCALAPPDATA%\Roots\translation-token.txt"
set "PINFILE=%LOCALAPPDATA%\Roots\compute-tls-sha256.txt"
if not exist "%KEYFILE%" (
  echo Start launch.cmd --lan first.
  exit /b 1
)
if not exist "%PINFILE%" (
  echo Start the updated launch.cmd --lan first to create the TLS certificate.
  exit /b 1
)
powershell -NoProfile -Command "$k=(Get-Content -LiteralPath $env:KEYFILE -Raw).Trim(); $p=(Get-Content -LiteralPath $env:PINFILE -Raw).Trim(); if ($k -notmatch '^[0-9a-fA-F]{64}$' -or $p -notmatch '^[0-9a-fA-F]{64}$') { Write-Error 'Invalid Roots connection key'; exit 1 }; $v=$k+':'+$p; Set-Clipboard -Value $v; Write-Host $v; Write-Host 'Roots connection key copied. Paste into the existing phone key field. Keep private.'"
exit /b %ERRORLEVEL%
