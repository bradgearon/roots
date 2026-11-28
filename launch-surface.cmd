@echo off
setlocal
pushd "%~dp0"
if not defined ROOTS_TREE_LIBRARY set "ROOTS_TREE_LIBRARY=%~dp0build\desktop-tree\Release\roots_tree.dll"
set "ROOTS_SURFACE_AGENT=%~dp0build\desktop-surface\Release\roots-surface.exe"
set "EXPERIENCE=%~dp0src\Wds.Roots.Experience\bin\x64\Release\net9.0\Wds.Roots.Experience.dll"
if not exist "%EXPERIENCE%" set "EXPERIENCE=%~dp0src\Wds.Roots.Experience\bin\Release\net9.0\Wds.Roots.Experience.dll"
if not exist "%ROOTS_SURFACE_AGENT%" (
    echo Missing native Surface agent: "%ROOTS_SURFACE_AGENT%"
    goto missing
)
if not exist "%ROOTS_TREE_LIBRARY%" (
    echo Missing native Tree library: "%ROOTS_TREE_LIBRARY%"
    goto missing
)
if not exist "%EXPERIENCE%" (
    echo Missing managed Experience DLL in bin\x64\Release\net9.0 or bin\Release\net9.0.
    goto missing
)
rem Query commands never install anything. Explicit --no-ocr also skips provisioning.
if /i "%~1"=="--status" goto run
if /i "%~1"=="--search" goto run
for %%A in (%*) do if /i "%%~A"=="--no-ocr" goto run
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\Ensure-DesktopOcr.ps1"
if errorlevel 1 echo Roots OCR provisioning unavailable. Continuing with installed OCR or UI Automation.
:run
dotnet "%EXPERIENCE%" %*
set "RC=%ERRORLEVEL%"
popd
exit /b %RC%
:missing
echo Run build-surface.cmd first.
popd
exit /b 1
