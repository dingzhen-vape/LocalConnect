# server.py — 电脑端 WebSocket 服务端（纯网络逻辑，不含任何界面代码）
from __future__ import annotations

import asyncio
import io
import json
import typing

import PIL
import PIL.Image
import websockets
import win32clipboard
from loguru import logger

import Enums

if typing.TYPE_CHECKING:
    from UI import MainWindow  # noqa: TC004

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

    职责：接收手机发来的缩略图与原图。

    它直接持有 MainWindow，收到缩略图就调它 —— 界面完全不碰网络，
    网络这一侧知道界面。这是本项目刻意选的耦合方式：省掉一层回调注入。
    """

    def __init__(self, Window: MainWindow):
        self.MainWindow = Window
        self.thumbs: dict[str, bytes] = {}  # id -> JPEG 字节（内存缓存）
        self.current_ws: websockets.ServerConnection = None

    async def doFetchOriginalPicture(self, thumb_id: str):
        # 先取到局部变量：下面 await 期间 self.current_ws 可能被别的协程改掉
        ws = self.current_ws
        if ws is None:
            logger.warning("没有已连接的手机，无法请求原图")
            return
        try:
            await ws.send(json.dumps({"type": "fetch_original", "id": thumb_id}))
        except Exception as e:
            # 手机断开后 current_ws 还指着那条死连接，往它 send 必然失败。
            # 清掉它，否则下一次 Ctrl+C 还会撞上同一根死管道
            logger.error(f"请求原图失败: {type(e).__name__}: {e}")
            if self.current_ws is ws:
                self.current_ws = None

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

        while True:
            try:
                msg = await ws.recv()
            except websockets.exceptions.ConnectionClosed:
                # 手机异常断开（没发 close 帧）时 recv() 抛这个，正常关闭也走这里。
                # 连接结束是常态不是错误 —— 让它冒出去只会换来 websockets 一整段
                # "connection handler failed" 堆栈，而下面真正该执行的清理反而被跳过
                break

            if isinstance(msg, str):  # 文本帧 = 控制消息
                m = json.loads(msg)

                if m["type"] == "thumb_begin":
                    # 上一批已经结束（state 回到 IDLE）说明这是新一批的开头 → 清掉旧的。
                    # 不能见一个 thumb_begin 就清：它每张图都会发，清 500 次等于只剩最后一张
                    if state == Enums.States.IDLE:
                        self.thumbs.clear()
                        self.MainWindow.clearThumbnails()
                        logger.info("新的一批缩略图，已清空旧缓存与界面")

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
                    try:
                        sendToClipboard(getPictureDib(original_buf))
                        logger.info(f"接收完成: {m['id']}")
                    except Exception as e:
                        # 一张坏图不该把整条连接带走（那又会绕回上面那个清理被跳过的坑）
                        logger.warning(f"原图处理失败: {type(e).__name__}: {e}")
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

        # 只在「还是我这条连接」时才清 —— 否则会把后连上的那台手机顶掉
        if self.current_ws is ws:
            self.current_ws = None
        logger.info(f"客户端断开: {ws.remote_address}")
