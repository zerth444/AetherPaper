import json
import socket
import struct
import sys

host = sys.argv[1] if len(sys.argv) > 1 else "127.0.0.1"
port = int(sys.argv[2]) if len(sys.argv) > 2 else 25565


def varint(n):
    out = b""
    while True:
        b = n & 0x7F
        n >>= 7
        out += struct.pack("B", b | (0x80 if n else 0))
        if not n:
            return out


def read_varint(s):
    n = shift = 0
    while True:
        b = s.recv(1)[0]
        n |= (b & 0x7F) << shift
        if not b & 0x80:
            return n
        shift += 7


s = socket.create_connection((host, port), timeout=10)
handshake = varint(0) + varint(776) + varint(len(host)) + host.encode() \
    + struct.pack(">H", port) + varint(1)
s.sendall(varint(len(handshake)) + handshake)
s.sendall(varint(1) + varint(0))
length = read_varint(s)
pid = read_varint(s)
str_len = read_varint(s)
data = b""
while len(data) < str_len:
    chunk = s.recv(str_len - len(data))
    if not chunk:
        break
    data += chunk
info = json.loads(data.decode("utf-8"))
print("version:", info.get("version", {}).get("name"))
print("motd:", info.get("description"))
print("players:", info.get("players"))
