@echo off
setlocal
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\Build-Android.ps1" %*
exit /b %ERRORLEVEL%
