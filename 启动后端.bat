@echo off
rem ===========================================================================
rem  NQX - Backend only (SpringBoot on 8080)
rem ---------------------------------------------------------------------------
rem  Double-click to start only the backend. Useful when you just want to
rem  test APIs / open Swagger, without the admin console.
rem
rem  NOTE: ASCII-only (see the note in the admin console .bat).
rem ===========================================================================
setlocal
cd /d "%~dp0"

title NQX Backend

powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\start-local.ps1"

if errorlevel 1 (
    echo.
    echo [FAILED] Backend failed to start. Check the messages above.
)
echo.
echo Press any key to close this window.
echo (The backend keeps running in the background.)
pause >nul
endlocal
