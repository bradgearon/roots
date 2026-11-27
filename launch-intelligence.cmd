@echo off
setlocal
cd /d "%~dp0"
set "ROOTS_INTELLIGENCE_LAN=--lan"
for %%A in (%*) do if "%%~A"=="--local-only" set "ROOTS_INTELLIGENCE_LAN="
if defined ROOTS_INTELLIGENCE_LAN (
    powershell.exe -NoProfile -ExecutionPolicy Bypass -File scripts\Ensure-LanFirewall.ps1 -Port 8221
    if errorlevel 1 echo Roots intelligence LAN firewall setup was not completed. Continuing launch.
)
dotnet run --project src\Wds.Roots.Host -c Release -p:RunWorkingDirectory="%CD%" -- --intelligence %ROOTS_INTELLIGENCE_LAN% %*
exit /b %ERRORLEVEL%
