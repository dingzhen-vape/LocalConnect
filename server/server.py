# server.py
import asyncio
import json
from pathlib import Path

import websockets

UPLOAD_DIR = Path(__file__).parent / "uploads"


async def handler(ws):
    # parents=True 会连着父目录一起建；写 True 是因为第一次运行时 uploads 还不存在
    UPLOAD_DIR.mkdir(parents=True, exist_ok=True)

    # 连上就先打个招呼。这一步让客户端知道「服务端活着、协议对上了」。
    # 少了它，客户端会一直安静地等在 onMessage 里，看起来像「连上了却没反应」。
    await ws.send(json.dumps({"type": "hello", "server": "py-localconnect"}))

    name = None                              # 记下客户端宣告的文件名
    async for msg in ws:                     # 一条连接可以收很多帧
        if isinstance(msg, str):             # 文本帧 = 控制消息
            m = json.loads(msg)
            if m["type"] == "upload_begin":
                name = m["name"]
                print("开始接收:", name, flush=True)
            elif m["type"] == "upload_end":
                # 收完了给客户端一个回执，否则客户端不知道服务端到底写成功没有
                await ws.send(json.dumps({"type": "ack", "saved": name}))
                print("接收完成:", name, flush=True)
        else:                                # 二进制帧 = 文件内容
            with open(UPLOAD_DIR / name, "ab") as f:   # ab = 追加写入二进制
                f.write(msg)
            print("收到一块:", len(msg), "字节", flush=True)


async def main():
    async with websockets.serve(handler, "0.0.0.0", 8000):
        print("监听 ws://0.0.0.0:8000 ...", flush=True)
        await asyncio.Future()               # 永远等下去，别让程序退出


asyncio.run(main())
