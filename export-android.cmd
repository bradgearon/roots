@echo off
setlocal
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\Export-Android.ps1" %*
exit /b %ERRORLEVEL%
