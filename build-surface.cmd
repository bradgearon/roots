@echo off
setlocal
pushd "%~dp0"
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\Build-DesktopSurface.ps1" %*
set "RC=%ERRORLEVEL%"
popd
exit /b %RC%
