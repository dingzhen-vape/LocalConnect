# server.py — 电脑端 WebSocket 服务端（纯网络逻辑，不含任何界面代码）
import asyncio
import io
import json
import typing
from pathlib import Path

import PIL
import PIL.Image
import websockets
import win32clipboard
from loguru import logger

import Enums

if typing.TYPE_CHECKING:
    from UI import MainWindow  # noqa: TC004

# 模块级常量：全大写下划线
UPLOAD_DIR = Path(__file__).parent / "uploads"

# 监听端口。app.py 的单实例检测也用它，所以抽出来避免两处硬编码。
SERVER_PORT = 8000
# ============================================================================
#  剪贴板（阶段二用，暂未接入）
# ============================================================================


def sendToClipboard(dib: bytes):
    """把 CF_DIB 数据写进 Windows 剪贴板。"""
    win32clipboard.OpenClipboard()
    try:
        win32clipboard.EmptyClipboard()
        win32clipboard.SetClipboardData(win32clipboard.CF_DIB, dib)
    finally:
        win32clipboard.CloseClipboard()


def getPictureDib(data: bytes) -> bytes:
    """把 JPEG/PNG 字节转成剪贴板需要的 CF_DIB 数据。

    Windows 粘贴图片读的是 CF_DIB，所以先转 BMP 再去掉 14 字节文件头。
    """
    image = PIL.Image.open(io.BytesIO(data))
    if image.mode != "RGB":
        image = image.convert("RGB")
    buf = io.BytesIO()
    image.save(buf, format="BMP")
    return buf.getvalue()[14:]


# ============================================================================
#  服务端核心
# ============================================================================


class ServerCore:
    """WebSocket 服务端。

    职责：接收手机发来的缩略图，存进内存，并通知外部。

    它【不 import 任何 Qt 类】——只通过回调把消息传出去。
    好处：网络逻辑能脱离界面独立测试，将来换界面也不需要改这里。
    """

    def __init__(self, Window: MainWindow):
        """
        :param on_thumbnail: 每收到一张缩略图就调用，参数是 (id, JPEG 字节)
        """
        self.MainWindow = Window
        self.thumbs: dict[str, bytes] = {}  # id -> JPEG 字节（内存缓存）
        self.current_ws: websockets.ServerConnection = None

    async def doFetchOriginalPicture(self, thumb_id: str):
        if self.current_ws is None:
            logger.warning("没有已连接的手机，无法请求原图")
            return
        await self.current_ws.send(
            json.dumps({"type": "fetch_original", "id": thumb_id})
        )

    async def start(self):
        """启动服务，并一直运行下去。"""
        async with websockets.serve(self.handleConnection, "0.0.0.0", SERVER_PORT):
            logger.info(f"监听 ws://0.0.0.0:{SERVER_PORT} ...")
            await asyncio.Future()  # 永远等下去，别让协程退出

    async def handleConnection(self, ws: websockets.ServerConnection):
        """处理一条客户端连接。"""
        logger.info(f"客户端接入: {ws.remote_address}")

        # 连上先握手：让客户端知道「服务端活着」
        await ws.send(json.dumps({"type": "hello", "server": "py-localconnect"}))

        self.current_ws = ws
        thumb_id = None  # 正在收的缩略图 id
        state = Enums.States.IDLE
        mode = None
        original_buf = bytearray()

        async for msg in ws:
            if isinstance(msg, str):  # 文本帧 = 控制消息
                m = json.loads(msg)

                if m["type"] == "thumb_begin":
                    thumb_id = str(m["id"])  # 统一成字符串
                    state = Enums.States.UP_LOADING
                    mode = Enums.Modes.THUMB
                    logger.debug(f"开始接收缩略图: {thumb_id}")

                elif m["type"] == "thumb_end":
                    logger.debug(f"缩略图 {m['id']} 接收完成")

                elif m["type"] == "thumb_done":
                    logger.info(
                        f"本批共 {m['count']} 张，内存中累计 {len(self.thumbs)} 张"
                    )
                    state = Enums.States.IDLE
                    mode = None
                elif m["type"] == "original_begin":
                    thumb_id = str(m["id"])
                    original_buf = bytearray()
                    logger.info(f"开始接收原图: {m['id']}")
                    state = Enums.States.DOWN_LOADING
                    mode = Enums.Modes.FULL
                elif m["type"] == "original_end":
                    sendToClipboard(getPictureDib(original_buf))
                    logger.info(f"接收完成: {m['id']}")
                    state = Enums.States.IDLE
                    mode = None
                    original_buf = bytearray()  # 丢掉引用，让那几 MB 立刻可回收

            else:  # 二进制帧
                if mode == Enums.Modes.THUMB and state == Enums.States.UP_LOADING:
                    # 存内存 + 立刻通知界面（不等 thumb_done，否则要等好几秒）
                    self.thumbs[thumb_id] = msg
                    logger.debug(f"缩略图 {thumb_id} 已存入内存: {len(msg)} 字节")
                    # 更新界面
                    self.MainWindow.addThumbnail(thumb_id, msg)
                elif mode == Enums.Modes.FULL and state == Enums.States.DOWN_LOADING:
                    original_buf.extend(msg)
        logger.info(f"客户端断开: {ws.remote_address}")
