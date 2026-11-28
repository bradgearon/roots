@echo off
setlocal
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\Get-IntelligenceKey.ps1"
exit /b %ERRORLEVEL%
