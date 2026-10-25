@echo off
rem ===========================================================================
rem  NQX - Start ALL (backend + admin console)
rem ---------------------------------------------------------------------------
rem  Double-click this file to start:
rem    1. SpringBoot backend on 8080
rem    2. Admin console dev server on 5173
rem  Both keep running in the background after this window closes.
rem
rem  Use "stop all.bat" to stop them.
rem
rem  NOTE: ASCII-only (see the note in the admin console .bat).
rem ===========================================================================
setlocal
cd /d "%~dp0"

title NQX Start All

powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\start-all.ps1"

if errorlevel 1 (
    echo.
    echo [FAILED] Startup failed. Check the messages above.
)
echo.
echo Press any key to close this window.
echo (The backend and admin console keep running in the background.)
pause >nul
endlocal
