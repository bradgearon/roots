@echo off
setlocal
cd /d "%~dp0"
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\Install-LanguageDetectorModel.ps1
if errorlevel 1 exit /b %ERRORLEVEL%
dotnet run --project src\Wds.Roots.Host -c Release -- %*
exit /b %ERRORLEVEL%
