"""生成通知栏用的小图标（白色锁形，透明底）。一次性工具，可随时删除。"""
import math
import struct
import zlib
import os

SIZE = 72


def new_canvas(size):
    return [[(0, 0, 0, 0) for _ in range(size)] for _ in range(size)]


def put(px, x, y, c=(255, 255, 255, 255)):
    if 0 <= x < SIZE and 0 <= y < SIZE:
        px[y][x] = c


def main():
    px = new_canvas(SIZE)
    # 锁体
    for y in range(34, 64):
        for x in range(18, 55):
            put(px, x, y)
    # 锁梁（半圆弧）
    cx, cy, r, th = 36, 34, 13, 5
    for deg in range(180, 361):
        a = math.radians(deg)
        for t in range(th):
            rr = r - t / 2.0
            x = int(round(cx + rr * math.cos(a)))
            y = int(round(cy + rr * math.sin(a)))
            put(px, x, y)
            put(px, x + 1, y)
    # 锁孔
    for y in range(44, 52):
        for x in range(32, 41):
            if (x - 36) ** 2 + (y - 47) ** 2 <= 9:
                put(px, x, y, (0, 0, 0, 0))
    for y in range(50, 58):
        for x in range(35, 38):
            put(px, x, y, (0, 0, 0, 0))

    raw = bytearray()
    for row in px:
        raw.append(0)
        for (r, g, b, a) in row:
            raw += bytes((r, g, b, a))

    def chunk(tag, data):
        return (struct.pack('>I', len(data)) + tag + data +
                struct.pack('>I', zlib.crc32(tag + data) & 0xFFFFFFFF))

    png = b'\x89PNG\r\n\x1a\n'
    png += chunk(b'IHDR', struct.pack('>IIBBBBB', SIZE, SIZE, 8, 6, 0, 0, 0))
    png += chunk(b'IDAT', zlib.compress(bytes(raw), 9))
    png += chunk(b'IEND', b'')

    out = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
                       'android', 'app', 'src', 'main', 'res', 'drawable-xxhdpi', 'ic_stat.png')
    os.makedirs(os.path.dirname(out), exist_ok=True)
    with open(out, 'wb') as f:
        f.write(png)
    print('written', out, len(png))


if __name__ == '__main__':
    main()
