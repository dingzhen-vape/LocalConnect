@echo off
rem Build LocalConnect.exe with Nuitka. See build-exe.ps1 for the details.
rem
rem Wrapped in a .bat because the default execution policy on this machine is
rem Restricted: running the .ps1 directly fails with "running scripts is disabled".
rem
rem NOTE: keep this file pure ASCII with CRLF endings, cmd.exe needs both.

powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0build-exe.ps1"

echo.
pause
