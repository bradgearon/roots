@echo off
setlocal
pushd "%~dp0"
if not defined ROOTS_TREE_LIBRARY set "ROOTS_TREE_LIBRARY=%~dp0build\desktop-tree\Release\roots_tree.dll"
set "ROOTS_SURFACE_AGENT=%~dp0build\desktop-surface\Release\roots-surface.exe"
set "EXPERIENCE=%~dp0src\Wds.Roots.Experience\bin\Release\net9.0\Wds.Roots.Experience.dll"
if not exist "%ROOTS_SURFACE_AGENT%" goto missing
if not exist "%ROOTS_TREE_LIBRARY%" goto missing
if not exist "%EXPERIENCE%" goto missing
dotnet "%EXPERIENCE%" %*
set "RC=%ERRORLEVEL%"
popd
exit /b %RC%
:missing
echo Run build-surface.cmd first.
popd
exit /b 1
