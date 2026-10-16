# 把服务端编译成单文件 exe（用 Nuitka，不用 PyInstaller）。
#
# 产物：server/build/LocalConnect.exe（约 25 MB）
#
# ---------------------------------------------------------------------------
# 首次使用前，先准备编译环境（只做一次）
# ---------------------------------------------------------------------------
#   uv python install 3.12
#   uv venv --python 3.12 server/.venv-build
#   uv pip install --python server/.venv-build/Scripts/python.exe --no-cache -r server/requirements.txt nuitka
#
# 为什么要单独搞一个 3.12：Nuitka 从 Python 3.13 起不再支持 MinGW 后端
# （会直接报 "cannot use '--mingw64' on Python version 3.13 or higher"），
# 而退回去用 MSVC 得装 2~4 GB 的 VS Build Tools。装个 3.12 便宜得多。
# 这个 venv 只用来编译，不影响 server/.venv 里那套 Python 3.14 的运行环境。
#
# ---------------------------------------------------------------------------
# 下面这些参数不是随便写的，每个都对应一个踩过的坑
# ---------------------------------------------------------------------------
#   NUITKA_CACHE_DIR : Nuitka 默认缓存在 %LOCALAPPDATA%\Nuitka，
#                      这台机器上建不了（failed to create cache directory）
#   TEMP / TMP       : 系统 %TEMP% 不可写，gcc 链接时会报
#                      "Cannot create temporary file ... Error 3"
#   --onefile-tempdir-spec : onefile 是「运行时解压到临时目录再启动」，
#                      默认解压到 %TEMP%（同样不可写，报 failed to open app.dll）。
#                      指到 exe 自己旁边，所以 exe 别放到 Program Files 这类只读位置
#   --include-package : websockets 用惰性导入（imports.py 里的 __getattr__），
#                      静态分析看不见，不加就会 ModuleNotFoundError: websockets.asyncio
# ---------------------------------------------------------------------------

$ErrorActionPreference = 'Stop'

$root = Split-Path $PSScriptRoot -Parent
$python = Join-Path $root 'server\.venv-build\Scripts\python.exe'

if (-not (Test-Path $python)) {
    Write-Host "找不到编译用的 Python: $python"
    Write-Host "请先按本文件顶部的说明准备 server/.venv-build"
    exit 1
}

$env:NUITKA_CACHE_DIR = Join-Path $root '.nuitka-cache'
$env:TEMP = Join-Path $root '.tmp'
$env:TMP = $env:TEMP
New-Item -ItemType Directory -Path $env:TEMP -Force | Out-Null

Push-Location (Join-Path $root 'server')
try {
    & $python -m nuitka `
        --standalone `
        --onefile `
        --mingw64 `
        --enable-plugin=pyqt6 `
        --assume-yes-for-downloads `
        --windows-console-mode=force `
        '--onefile-tempdir-spec={PROGRAM_DIR}/LocalConnect_tmp' `
        --include-package=websockets `
        --include-package=qasync `
        --include-package=PIL `
        --output-dir=build `
        --output-filename=LocalConnect.exe `
        app.py
} finally {
    Pop-Location
}

$exe = Join-Path $root 'server\build\LocalConnect.exe'
if (Test-Path $exe) {
    $mb = [math]::Round((Get-Item $exe).Length / 1MB, 1)
    Write-Host ""
    Write-Host "完成: $exe ($mb MB)"
} else {
    Write-Host "没有生成 exe，往上翻看看编译错在哪"
    exit 1
}
