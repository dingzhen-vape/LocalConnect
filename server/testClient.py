# client_test.py
import asyncio
import json
import os

import websockets

URL = "ws://127.0.0.1:8000"
FILE = "test.bin"          # 要发的文件，放 server/ 目录下
CHUNK = 64 * 1024          # 每块 64 KB


async def main():
    async with websockets.connect(URL) as ws:          # 连上
        await ws.send(json.dumps({                     # 1. 宣告要发什么
            "type": "upload_begin",
            "name": os.path.basename(FILE),
            "size": os.path.getsize(FILE),
        }))
        with open(FILE, "rb") as f:
            while chunk := f.read(CHUNK):              # 2. 一块一块发
                await ws.send(chunk)
        await ws.send(json.dumps({"type": "upload_end"}))   # 3. 发完了
        print("发送完成", flush=True)


asyncio.run(main())