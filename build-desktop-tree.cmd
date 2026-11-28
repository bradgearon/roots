@echo off
setlocal
cd /d "%~dp0"
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\Build-DesktopTree.ps1 %*
exit /b %ERRORLEVEL%
