"""Minimal RCON client for the local dev server: python rcon.py "command" ["command" ...]"""
import socket
import struct
import sys
import time


def packet(sock, rid, kind, body):
    data = struct.pack('<ii', rid, kind) + body.encode('utf-8') + b'\x00\x00'
    sock.sendall(struct.pack('<i', len(data)) + data)


def receive(sock):
    size = struct.unpack('<i', sock.recv(4))[0]
    data = b''
    while len(data) < size:
        data += sock.recv(size - len(data))
    rid, kind = struct.unpack('<ii', data[:8])
    return rid, data[8:-2].decode('utf-8', 'replace')


def main():
    sock = socket.create_connection(('127.0.0.1', 25575), timeout=600)
    packet(sock, 1, 3, 'citybuilder')
    receive(sock)
    for i, command in enumerate(sys.argv[1:]):
        start = time.time()
        packet(sock, 10 + i, 2, command)
        _, body = receive(sock)
        print(f'[{time.time() - start:6.1f}s] {command} -> {body}')
    sock.close()


if __name__ == '__main__':
    main()
