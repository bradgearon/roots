@echo off
setlocal
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\Manage-InternalDevice.ps1" %*
exit /b %ERRORLEVEL%
