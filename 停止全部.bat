@echo off
rem ===========================================================================
rem  NQX - Stop ALL (backend 8080 + admin console 5173)
rem ---------------------------------------------------------------------------
rem  Double-click to stop both services.
rem  Safe to run repeatedly: it reports "not running" instead of failing.
rem
rem  NOTE: ASCII-only (see the note in the admin console .bat).
rem ===========================================================================
setlocal
cd /d "%~dp0"

title NQX Stop All

powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\stop-all.ps1"

echo.
echo Press any key to close this window.
pause >nul
endlocal
