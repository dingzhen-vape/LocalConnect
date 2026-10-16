# Build the server into a single-file exe with Nuitka (not PyInstaller).
#
# Output: server/build/LocalConnect.exe (~25 MB)
#
# ---------------------------------------------------------------------------
# One-time setup
# ---------------------------------------------------------------------------
#   uv python install 3.12
#   uv venv --python 3.12 server/.venv-build
#   uv pip install --python server/.venv-build/Scripts/python.exe --no-cache -r server/requirements.txt nuitka
#
# Why a separate 3.12: Nuitka dropped the MinGW backend for Python 3.13+
# ("cannot use '--mingw64' on Python version 3.13 or higher"). The other way is
# MSVC via VS Build Tools, a 2-4 GB download. This venv is only used for
# building; it does not touch the runtime venv in server/.venv.
#
# ---------------------------------------------------------------------------
# Every flag below exists because of a specific failure. Do not "clean up".
# ---------------------------------------------------------------------------
#   NUITKA_CACHE_DIR
#       Nuitka caches under %LOCALAPPDATA%\Nuitka, which is not writable on
#       this machine -> "failed to create cache directory".
#   TEMP / TMP
#       The system %TEMP% is not writable either -> the gcc link step fails
#       with "Cannot create temporary file ... Error 3".
#   --onefile-tempdir-spec
#       A onefile exe extracts its payload to a temp dir at startup; the
#       default is %TEMP% (not writable) -> "failed to open app.dll".
#       Pointed at the exe's own directory, so do NOT ship the exe from a
#       read-only location such as C:\Program Files.
#   --include-package
#       websockets imports lazily (__getattr__ in imports.py), which static
#       analysis cannot see -> "ModuleNotFoundError: websockets.asyncio".
#   --windows-icon-from-ico
#       The exe icon. Windows only accepts .ico. icon/localconnect.ico is
#       generated from icon/<source>.png; to regenerate after changing the
#       source image:
#         python -c "from PIL import Image; Image.open('icon/x.png').save('icon/localconnect.ico', sizes=[(16,16),(32,32),(48,48),(64,64),(128,128),(256,256)])"
#   ErrorActionPreference
#       Nuitka writes progress to stderr. PowerShell turns native stderr into
#       an error record, and under 'Stop' that aborts the build halfway.
#
# NOTE: keep this file pure ASCII. Windows PowerShell 5.1 decodes .ps1 as GBK,
# and a UTF-8 file without BOM breaks on the first non-ASCII character
# ("string is missing the terminator"). The .bat wrapper next to this file has
# the same constraint, plus it needs CRLF line endings.
# ---------------------------------------------------------------------------

$ErrorActionPreference = 'Stop'

$root = Split-Path $PSScriptRoot -Parent
$python = Join-Path $root 'server\.venv-build\Scripts\python.exe'
$icon = Join-Path $root 'server\icon\localconnect.ico'

if (-not (Test-Path $python)) {
    Write-Host "Build interpreter not found: $python"
    Write-Host "See the one-time setup notes at the top of this file."
    exit 1
}
if (-not (Test-Path $icon)) {
    Write-Host "Icon not found: $icon"
    exit 1
}

$env:NUITKA_CACHE_DIR = Join-Path $root '.nuitka-cache'
$env:TEMP = Join-Path $root '.tmp'
$env:TMP = $env:TEMP
New-Item -ItemType Directory -Path $env:TEMP -Force | Out-Null

Push-Location (Join-Path $root 'server')
try {
    # Loosen EAP around the native call only: Nuitka's progress goes to stderr.
    $prevEap = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'

    & $python -m nuitka `
        --standalone `
        --onefile `
        --mingw64 `
        --enable-plugin=pyqt6 `
        --assume-yes-for-downloads `
        --windows-console-mode=force `
        --windows-icon-from-ico=icon/localconnect.ico `
        '--onefile-tempdir-spec={PROGRAM_DIR}/LocalConnect_tmp' `
        --include-package=websockets `
        --include-package=qasync `
        --include-package=PIL `
        --output-dir=build `
        --output-filename=LocalConnect.exe `
        app.py

    $nuitkaExit = $LASTEXITCODE
    $ErrorActionPreference = $prevEap
    if ($nuitkaExit -ne 0) { Write-Host "nuitka exited with code $nuitkaExit" }
} finally {
    Pop-Location
}

$exe = Join-Path $root 'server\build\LocalConnect.exe'
if (Test-Path $exe) {
    $mb = [math]::Round((Get-Item $exe).Length / 1MB, 1)
    Write-Host ""
    Write-Host "Built: $exe ($mb MB)"
} else {
    Write-Host "No exe produced; scroll up for the compiler error."
    exit 1
}
