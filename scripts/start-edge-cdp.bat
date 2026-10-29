@echo off
setlocal

rem Edge を CDP デバッグモードで起動します。
rem 起動後、この Edge で企業 Box にログインしてください。

set EDGE_EXE=%ProgramFiles(x86)%\Microsoft\Edge\Application\msedge.exe
if not exist "%EDGE_EXE%" (
    set EDGE_EXE=%ProgramFiles%\Microsoft\Edge\Application\msedge.exe
)
if not exist "%EDGE_EXE%" (
    echo Edge が見つかりません。
    exit /b 1
)

set SCRIPT_DIR=%~dp0
set PROFILE_DIR=%SCRIPT_DIR%..\edge-cdp-profile
set CDP_PORT=9222

if not exist "%PROFILE_DIR%" mkdir "%PROFILE_DIR%"

echo Edge を CDP モードで起動します...
echo   CDP URL : http://localhost:%CDP_PORT%
echo   Profile : %PROFILE_DIR%
echo.
echo 企業 Box にログイン後、別ターミナルで以下を実行してください:
echo   scripts\run-watch.bat

start "" "%EDGE_EXE%" ^
    --remote-debugging-port=%CDP_PORT% ^
    --user-data-dir="%PROFILE_DIR%" ^
    --no-first-run ^
    --no-default-browser-check

endlocal
