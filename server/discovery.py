# discovery.py — 让手机自动找到这台电脑
#
# 手机往多播组发一个探测包，本模块收到后单播回一个应答。
# 手机从应答包的源地址拿电脑 IP —— 不在包里写 IP 是有原因的：
# 一台机器可能有多张网卡（这台还有 Radmin VPN 的 26.177.45.55），
# 只有「实际把包送出去的那张」的地址，才是手机能连上的。
from __future__ import annotations

import asyncio
import json
import socket
import struct

from loguru import logger

# 落在 224.0.0.0/4 里即可。选这个值是因为 LocalSend 等工具在用，
# 路由器和 AP 见过这个组，被静默丢弃的概率比冷门地址低
MULTICAST_GROUP = "224.0.0.167"

# 1 = 只在本子网。给 AP 的转发留一跳余量，又不会扩散到路由器之外
MULTICAST_TTL = 2


class _DiscoverProtocol(asyncio.DatagramProtocol):
    """收到 discover 就回 discovered。"""

    def __init__(self, tcp_port: int):
        self.tcp_port = tcp_port
        self.transport: asyncio.DatagramTransport = None

    def connection_made(self, transport):
        self.transport = transport

    def datagram_received(self, data: bytes, addr: tuple):
        try:
            msg = json.loads(data.decode("utf-8"))
        except (UnicodeDecodeError, json.JSONDecodeError):
            # 同一个组里可能有别的程序的多播包，解析不了就忽略
            return
        if msg.get("type") != "discover" or msg.get("app") != "localconnect":
            return

        # 单播回发起方：addr 就是刚才那个包的 (源 IP, 源端口)。
        # 手机据此得知电脑地址，所以这里不必再往组里发一次
        reply = json.dumps(
            {"type": "discovered", "app": "localconnect", "ws": self.tcp_port}
        ).encode("utf-8")
        self.transport.sendto(reply, addr)
        logger.info(f"发现请求来自 {addr[0]}，已回应")


def _makeSocket(port: int) -> socket.socket:
    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    # Windows 上 SO_REUSEADDR 的含义是「允许多个 socket 绑同一端口」。
    # 单实例时用不到，留着能避免「上次没退干净」导致下次绑定失败
    sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    sock.bind(("0.0.0.0", port))

    # ★ 加入组之后才会收到发往 224.0.0.167 的包。
    #   INADDR_ANY 表示在所有网卡上都加入；写死某张网卡的地址会漏掉其余网卡
    mreq = struct.pack(
        "4s4s", socket.inet_aton(MULTICAST_GROUP), socket.inet_aton("0.0.0.0")
    )
    sock.setsockopt(socket.IPPROTO_IP, socket.IP_ADD_MEMBERSHIP, mreq)
    sock.setsockopt(socket.IPPROTO_IP, socket.IP_MULTICAST_TTL, MULTICAST_TTL)
    return sock


async def start(port: int):
    """启动发现服务。失败只记一条警告 —— 手机仍可手动填地址。"""
    loop = asyncio.get_running_loop()
    try:
        sock = _makeSocket(port)
        await loop.create_datagram_endpoint(
            lambda: _DiscoverProtocol(port), sock=sock
        )
    except OSError as e:
        logger.warning(f"自动发现启动失败（手机需手动填地址）: {type(e).__name__}: {e}")
        return
    logger.info(f"自动发现已就绪: 多播 {MULTICAST_GROUP}:{port}")
