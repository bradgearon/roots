@echo off
setlocal
cd /d "%~dp0"
if not exist native\whirlwind\target\release\roots-whirlwind.exe (
  echo Build the Whirlwind desktop engine first: build-whirlwind.cmd desktop
  exit /b 1
)
dotnet run --project src\Wds.Roots.Host -c Release -p:RunWorkingDirectory="%CD%" -- --intelligence --local-only --whirlwind
exit /b %ERRORLEVEL%
