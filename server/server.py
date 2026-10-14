# server.py
import asyncio
import io
import json
from pathlib import Path

import PIL.Image

import Enums
import websockets
import PIL
import win32clipboard

UPLOAD_DIR = Path(__file__).parent / "uploads"


def sendToClipBoard(DIB: bytes):
    win32clipboard.OpenClipboard()
    try:
        win32clipboard.EmptyClipboard()
        win32clipboard.SetClipboardData(win32clipboard.CF_DIB, DIB)
    finally:
        win32clipboard.CloseClipboard()

def getPictureDIB(data: bytes) -> bytes:
    """把 JPEG/PNG 字节转成剪贴板需要的 CF_DIB 数据。

    Windows 粘贴图片读的是 CF_DIB，所以必须转成 BMP 再去掉 14 字节文件头。
    """
    # ① 解码。Pillow 会自动识别 JPEG / PNG / 其他格式
    image = PIL.Image.open(io.BytesIO(data))

    # ② 转成 RGB：剪贴板的 CF_DIB 不支持 alpha，JPEG 也不支持 RGBA
    if image.mode != "RGB":
        image = image.convert("RGB")

    # ③ 存成 BMP（无损位图），不是 JPEG
    buf = io.BytesIO()
    image.save(buf, format="BMP")

    # ④ 去掉 BMP 的 14 字节文件头，剩下的才是 CF_DIB 要的数据
    return buf.getvalue()[14:]


async def handler(ws):
    # parents=True 会连着父目录一起建；写 True 是因为第一次运行时 uploads 还不存在
    UPLOAD_DIR.mkdir(parents=True, exist_ok=True)

    # 连上就先打个招呼。这一步让客户端知道「服务端活着、协议对上了」。
    # 少了它，客户端会一直安静地等在 onMessage 里，看起来像「连上了却没反应」。
    await ws.send(json.dumps({"type": "hello", "server": "py-localconnect"}))

    name = None  # 记下客户端宣告的文件名
    thumb_id = None  # 新增：正在收的缩略图 id
    state = Enums.States.IDLE
    mode = None
    meta = {}
    
    async for msg in ws:  # 一条连接可以收很多帧
        if isinstance(msg, str):  # 文本帧 = 控制消息
            m = json.loads(msg)
            if m["type"] == "upload_begin":
                name = m["name"]
                state = Enums.States.UP_LOADING
                mode = Enums.Modes.FILE
                print("开始接收:", name, flush=True)

            elif m["type"] == "upload_end":
                # 收完了给客户端一个回执，否则客户端不知道服务端到底写成功没有
                await ws.send(json.dumps({"type": "ack", "saved": name}))
                print("接收完成:", name, flush=True)
                state = Enums.States.IDLE
            elif m["type"] == "thumb_begin":
                thumb_id = m["id"]
                name = f"thumb_{thumb_id}.jpg"  # 新增：缩略图的文件名
                print("开始接收缩略图:", thumb_id, flush=True)
                state = Enums.States.UP_LOADING
                mode = Enums.Modes.THUMB
            elif m["type"] == "thumb_end":
                await ws.send(json.dumps({"type": "ack", "saved": name}))
                print(f"{m['id']} 缩略图接收完成", flush=True)
            elif m["type"] == "thumb_done":
                print(f"已接收{m['count']}张", flush=True)
                state = Enums.States.IDLE
                print(meta)

        else:  # 二进制帧 = 文件内容
            if mode == Enums.Modes.THUMB and state == Enums.States.UP_LOADING:
                # 新增：正在接收缩略图
                print("正在存储缩略图:", thumb_id, flush=True)
                meta[thumb_id] = msg
            


async def main():
    async with websockets.serve(handler, "0.0.0.0", 8000):
        print("监听 ws://0.0.0.0:8000 ...", flush=True)
        await asyncio.Future()  # 永远等下去，别让程序退出


asyncio.run(main())
