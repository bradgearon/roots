@echo off
setlocal
cd /d "%~dp0"
set "ATLAS_PY=%~dp0.roots-atlas\venv\Scripts\python.exe"
if /i "%~1"=="setup" (
    if not exist "%ATLAS_PY%" python -m venv .roots-atlas\venv
    if errorlevel 1 exit /b 1
    "%ATLAS_PY%" -m pip install -r tools\cognitive-atlas\requirements.txt
    exit /b %ERRORLEVEL%
)
if /i "%~1"=="test" (
    python tools\cognitive-atlas\test_atlas.py
    exit /b %ERRORLEVEL%
)
if not exist "%ATLAS_PY%" (
    echo Run atlas.cmd setup first.
    exit /b 1
)
"%ATLAS_PY%" tools\cognitive-atlas\atlas.py %*
exit /b %ERRORLEVEL%
