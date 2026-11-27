@echo off
setlocal
cd /d "%~dp0"
set "ROOTS_LAN=--lan"
for %%A in (%*) do if "%%~A"=="--local-only" set "ROOTS_LAN="
if defined ROOTS_LAN (
    powershell -NoProfile -ExecutionPolicy Bypass -File scripts\Ensure-LanFirewall.ps1
    if errorlevel 1 echo Roots LAN firewall setup was not completed. Continuing launch.
)
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\Install-LanguageDetectorModel.ps1
if errorlevel 1 exit /b %ERRORLEVEL%
dotnet run --project src\Wds.Roots.Host -c Release -p:RunWorkingDirectory="%CD%" -- %ROOTS_LAN% %*
exit /b %ERRORLEVEL%
