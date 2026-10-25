@echo off
rem ===========================================================================
rem  NQX - Admin Console (Vue 3 + Element Plus)
rem ---------------------------------------------------------------------------
rem  Double-click this file to start the admin console.
rem  The window IS the dev server console: closing it stops the server.
rem
rem  NOTE: this .bat is intentionally ASCII-only. Chinese characters inside a
rem  .bat file get mangled by the console code page, and a mangled path breaks
rem  "%~dp0". Chinese output is printed by the PowerShell script instead, which
rem  sets the console encoding itself.
rem ===========================================================================
setlocal
cd /d "%~dp0"

title NQX Admin Console

powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\start-frontend.ps1"

rem Keep the window open on failure so the error is readable.
if errorlevel 1 (
    echo.
    echo [FAILED] The admin console exited with an error.
    echo Check the messages above.
    pause
)
endlocal
