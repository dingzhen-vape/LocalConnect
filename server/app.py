"""
LocalConnect 电脑端入口

把三件事组合起来：
    Qt 界面（UI.py）  +  WebSocket 服务端（server.py）  +  事件循环桥接（qasync）

为什么需要 qasync：
    Qt 和 asyncio 各有一个「事件循环」，而主线程只能跑一个。
    qasync 把 Qt 的循环包装成 asyncio 的接口，于是
    loop.run_forever() 一个循环就能同时驱动界面和网络。
"""

import asyncio
import socket
import sys

from loguru import logger
from PyQt6.QtWidgets import QApplication
from qasync import QEventLoop

from server import SERVER_PORT, ServerCore
from UI import MainWindow

# 日志配置
logger.remove()
logger.add(
    sys.stdout,
    level="DEBUG",
    colorize=None,
    format=(
        "<green>{time:YYYY-MM-DD HH:mm:ss.SSS}</green> | "
        "<level>{level: <8}</level> | "
        "<cyan>{name}</cyan>:<cyan>{function}</cyan>:<cyan>{line}</cyan> - "
        "<level>{message}</level>"
    ),
    backtrace=True,
    diagnose=False,
)


def portIsBusy(port: int) -> bool:
    """检测端口是否已被占用，用来拦住"重复启动"。

    先 bind 再立刻 close：能绑上说明空闲。
    这不能 100% 排除竞态，但足以拦住常见的手滑重复运行。
    """
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as probe:
        try:
            probe.bind(("0.0.0.0", port))
            return False
        except OSError:
            return True


def getLocalIp() -> str:
    """猜出「手机该连哪个地址」。

    ⚠️ 别用 socket.gethostbyname(socket.gethostname())：Windows 上它经常返回
    虚拟网卡（VPN）的地址，或者干脆解析失败。
    这里 connect 一个外网地址 —— UDP connect 不会真的发包，只是逼系统查一次
    路由表，于是问出「出网走哪张网卡」，拿到的就是手机能连上的那个。
    """
    probe = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        probe.connect(("8.8.8.8", 80))
        return probe.getsockname()[0]
    except OSError:
        # 完全没网时（比如只有回环）没有可用的对外地址
        return "127.0.0.1"
    finally:
        probe.close()


def main() -> int:
    # 早检查：端口被占说明已有实例，直接退出并给出清晰提示，
    # 免得 websockets 抛出一大串 OSError 堆栈。
    if portIsBusy(SERVER_PORT):
        logger.error(
            f"端口 {SERVER_PORT} 已被占用 —— 可能已有一个 LocalConnect 在运行。"
            "请先关闭它，或检查是否有残留的 python 进程。"
        )
        return 1

    app = QApplication(sys.argv)

    # 把 Qt 的循环包成 asyncio 的事件循环
    loop = QEventLoop(app)
    asyncio.set_event_loop(loop)

    ip = getLocalIp()
    window = MainWindow()
    # 地址放进标题栏：换网络后 IP 会变，手机端要照着改，放这儿一眼就能看到
    window.setWindowTitle(f"LocalConnect — 手机连 {ip}:{SERVER_PORT}")
    window.show()

    # 服务端每收到一张缩略图就回调这里 → 直接更新界面。
    # 因为 qasync 让两者跑在同一线程，所以不需要跨线程信号。
    core = ServerCore(window)
    loop.create_task(core.start())

    def onFetchOriginal(thumb_id: str):
        loop.create_task(core.doFetchOriginalPicture(thumb_id))

    window.fetchRequested.connect(onFetchOriginal)

    logger.info(f"手机端请填: {ip}:{SERVER_PORT}")
    logger.info("手机连不上就先放行防火墙：以管理员身份运行 tools/allow-firewall.bat")
    logger.info("界面已就绪，等待手机连接…")

    with loop:
        loop.run_forever()

    return 0


if __name__ == "__main__":
    sys.exit(main())
