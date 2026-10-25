@echo off
rem ===========================================================================
rem  NQX - Verify local environment (end-to-end smoke check)
rem ---------------------------------------------------------------------------
rem  Run this AFTER starting the services (start all.bat / admin console.bat).
rem
rem  It walks the real HTTP paths: health -> admin HTML -> login via vite proxy
rem  -> menus -> image proxy -> upload -> miniprogram JS syntax.
rem
rem  Why: "BUILD SUCCESS" does not mean "it runs". Several past bugs (broken
rem  /files proxy, bad menu icon name, COS config missing) only showed up at
rem  runtime, not at compile time.
rem
rem  NOTE: ASCII-only (see the note in the admin console .bat).
rem ===========================================================================
setlocal
cd /d "%~dp0"

title NQX Verify

powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\verify-local.ps1"

echo.
echo Press any key to close this window.
pause >nul
endlocal
