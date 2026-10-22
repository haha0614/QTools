@echo off
setlocal EnableExtensions
rem Build both front-ends into their dist directories (M4-A single-port hosting).
rem Usage: bin\build-web.bat

set "SCRIPT_DIR=%~dp0"
for %%I in ("%SCRIPT_DIR%..\..") do set "PROJ_ROOT=%%~fI"
set "USER_DIR=%PROJ_ROOT%\QTools-C"
set "ADMIN_DIR=%PROJ_ROOT%\QTools-M"
set "USER_DIST=%USER_DIR%\dist"
set "ADMIN_DIST=%ADMIN_DIR%\dist"

echo [build-web] project root: %PROJ_ROOT%

set "NPM="
rem Probe npm.cmd specifically: bare "where npm" can return the extensionless sh wrapper, which "call" cannot run.
for /f "delims=" %%N in ('where npm.cmd 2^>nul') do (
    if not defined NPM set "NPM=%%N"
)
rem Prefer the portable node shipped on this drive (..\..\tools\node), so the
rem build keeps working on another machine without Node installed.
if not defined NPM if exist "%PROJ_ROOT%\..\..\tools\node\npm.cmd" (
    set "NPM=%PROJ_ROOT%\..\..\tools\node\npm.cmd"
)
if defined NPM (
    echo [build-web] npm found: %NPM%
) else (
    echo [ERROR] npm not found: neither on PATH nor in tools\node\npm.cmd
    echo [ERROR] install Node.js, or put the portable node under tools\node, then re-run.
    exit /b 1
)

rem npm itself is only a script: the real work is done by node.exe in the same
rem directory, and node_modules\.bin\vite.cmd invokes a bare "node". So the npm
rem directory must be on PATH, otherwise the portable setup fails with
rem   '"node"' is not recognized as an internal or external command
for %%I in ("%NPM%") do set "NODE_DIR=%%~dpI"
if defined NODE_DIR set "PATH=%NODE_DIR%;%PATH%"
if not exist "%NODE_DIR%node.exe" (
    echo [WARN] node.exe not found next to "%NPM%"; the build may fail with "node is not recognized".
)
echo [build-web] node dir on PATH: %NODE_DIR%

if not exist "%USER_DIR%\package.json" (
    echo [ERROR] missing %USER_DIR%\package.json
    exit /b 1
)
if not exist "%ADMIN_DIR%\package.json" (
    echo [ERROR] missing %ADMIN_DIR%\package.json
    exit /b 1
)

echo.
echo [build-web] [1/3] QTools-C : npm run build
pushd "%USER_DIR%"
call "%NPM%" run build
set "RC=%ERRORLEVEL%"
popd
if not "%RC%"=="0" (
    echo [ERROR] QTools-C build FAILED with exit code %RC%
    exit /b %RC%
)

echo.
echo [build-web] [2/3] QTools-M : npm run build:prod
pushd "%ADMIN_DIR%"
call "%NPM%" run build:prod
set "RC=%ERRORLEVEL%"
popd
if not "%RC%"=="0" (
    echo [ERROR] QTools-M build FAILED with exit code %RC%
    exit /b %RC%
)

echo.
echo [build-web] [3/3] verify dist artifacts
set "MISSING=0"
call :check "%USER_DIST%\index.html"
call :check "%USER_DIST%\media\index.html"
call :check "%USER_DIST%\media\app.js"
call :check "%USER_DIST%\media\app.css"
call :check "%USER_DIST%\media\photos.html"
call :check "%USER_DIST%\media\photo.js"
call :check "%ADMIN_DIST%\index.html"
if exist "%ADMIN_DIST%\static\js\*.js" (
    for %%A in ("%ADMIN_DIST%\static\js") do echo   OK   %%~fA\ ^(hashed chunks^)
) else (
    echo   MISS %ADMIN_DIST%\static\js\*.js
    set "MISSING=1"
)

if "%MISSING%"=="1" (
    echo.
    echo [WARN] some media chunks are missing: put them under QTools-C\public\media before building.
)
echo.
echo [build-web] DONE
exit /b 0

:check
if exist "%~1" (
    for %%A in ("%~1") do echo   OK   %~1  ^(%%~zA bytes^)
) else (
    echo   MISS %~1
    set "MISSING=1"
)
exit /b 0
