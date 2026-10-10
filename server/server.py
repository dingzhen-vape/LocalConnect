# server.py
import asyncio
import json
from pathlib import Path

import websockets

UPLOAD_DIR = Path(__file__).parent / "uploads"


async def handler(ws):
    UPLOAD_DIR.mkdir(exist_ok=True)          # 收之前先保证目录存在
    name = None                              # 记下客户端宣告的文件名
    async for msg in ws:                     # 一条连接可以收很多帧
        if isinstance(msg, str):             # 文本帧 = 控制消息
            m = json.loads(msg)
            if m["type"] == "upload_begin":
                name = m["name"]
                print("开始接收:", name, flush=True)
        else:                                # 二进制帧 = 文件内容
            with open(UPLOAD_DIR / name, "ab") as f:   # ab = 追加写入二进制
                f.write(msg)
            print("收到一块:", len(msg), "字节", flush=True)


async def main():
    async with websockets.serve(handler, "0.0.0.0", 8000):
        print("监听 ws://0.0.0.0:8000 ...", flush=True)
        await asyncio.Future()               # 永远等下去，别让程序退出


asyncio.run(main())