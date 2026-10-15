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

from server import SERVER_PORT, UPLOAD_DIR, ServerCore
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


def loadCachedThumbnails() -> dict[str, bytes]:
    """读取 uploads/ 里已有的缩略图，让界面一打开就有东西看。

    只看 thumb_*.jpg —— 这是我们约定给缩略图的命名。
    """
    result: dict[str, bytes] = {}
    if not UPLOAD_DIR.exists():
        return result

    for path in sorted(UPLOAD_DIR.glob("thumb_*.jpg")):
        thumb_id = path.stem.removeprefix("thumb_")
        try:
            result[thumb_id] = path.read_bytes()
        except OSError as e:
            logger.warning(f"读取缓存缩略图失败 {path.name}: {e}")
    return result


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

    window = MainWindow()
    window.show()

    # 默认加载 uploads/ 里已有的缩略图，方便一打开就确认界面是通的。
    # 加 --no-cache 可以跳过，用来纯测实时接收。
    if "--no-cache" not in sys.argv:
        for cached_id, cached_data in loadCachedThumbnails().items():
            window.addThumbnail(cached_id, cached_data)
        if window.thumbnailCount:
            logger.info(f"已从 uploads/ 加载 {window.thumbnailCount} 张缓存缩略图")

    # 服务端每收到一张缩略图就回调这里 → 直接更新界面。
    # 因为 qasync 让两者跑在同一线程，所以不需要跨线程信号。
    core = ServerCore(on_thumbnail=window.addThumbnail)
    loop.create_task(core.start())

    logger.info("界面已就绪，等待手机连接…")

    with loop:
        loop.run_forever()

    return 0


if __name__ == "__main__":
    sys.exit(main())
