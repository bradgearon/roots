@echo off
setlocal
set "KEYFILE=%LOCALAPPDATA%\Roots\translation-token.txt"
if not exist "%KEYFILE%" (
  echo Roots key not found. Start launch.cmd --lan once first.
  exit /b 1
)
powershell -NoProfile -Command "$k=(Get-Content -LiteralPath $env:KEYFILE -Raw).Trim(); if ($k -notmatch '^[0-9a-fA-F]{64}$') { Write-Error 'Invalid Roots key'; exit 1 }; Set-Clipboard -Value $k; Write-Host $k; Write-Host 'Roots key copied to clipboard. Keep it private.'"
exit /b %ERRORLEVEL%
