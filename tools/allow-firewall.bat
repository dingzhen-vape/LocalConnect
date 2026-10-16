@echo off
rem ===========================================================================
rem  Allow inbound TCP 8000 so the phone can reach LocalConnect.
rem
rem  Windows Firewall blocks inbound connections by default. The Android
rem  emulator tunnels through QEMU's user-mode NAT (which the host sees as a
rem  local connection), so it never touches the firewall. A real phone comes
rem  in over the real NIC and gets blocked.
rem
rem  Just double-click this file; it asks for admin rights via UAC itself.
rem  To undo:  netsh advfirewall firewall delete rule name="LocalConnect 8000"
rem
rem  NOTE: keep this file pure ASCII with CRLF line endings. cmd.exe reads
rem  .bat files as GBK and needs CRLF -- UTF-8 or LF will break the parser.
rem ===========================================================================

set PORT=8000
set RULENAME=LocalConnect %PORT%

rem "net session" fails for non-admin users -- the classic admin check
net session >nul 2>&1
if %errorlevel% neq 0 (
    echo Requesting administrator rights...
    powershell -NoProfile -Command "Start-Process -FilePath '%~f0' -Verb RunAs"
    exit /b
)

echo Adding inbound allow rule for TCP %PORT% ...

rem drop any previous rule with the same name first
netsh advfirewall firewall delete rule name="%RULENAME%" >nul 2>&1

netsh advfirewall firewall add rule ^
    name="%RULENAME%" ^
    dir=in ^
    action=allow ^
    protocol=TCP ^
    localport=%PORT% ^
    profile=any

if %errorlevel% equ 0 (
    echo.
    echo Done. TCP %PORT% is allowed for all network profiles.
    echo The phone should be able to connect now.
) else (
    echo.
    echo Failed. Make sure you approved the UAC prompt.
)

echo.
pause