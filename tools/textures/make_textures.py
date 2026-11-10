"""
Текстуры предметов Airship Voyage в стиле Minecraft: 16×16, малая палитра, тёмный контур по силуэту,
свет сверху слева. Рисуются кодом — правка и пересборка без графического редактора:

    python tools/textures/make_textures.py            # PNG в src/main/resources/assets/airshipvoyage/textures
    python tools/textures/make_textures.py --preview  # плюс лист-превью build/texture_preview.png

Нужен Pillow. Каждый спрайт — функция, которая закрашивает сетку материалами; общий проход даёт контур
и объём (свет/тень по соседям), затем поверх — детали точными цветами.
"""
import math
import os
import sys

from PIL import Image

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), '..', '..'))
ASSETS = os.path.join(ROOT, 'src', 'main', 'resources', 'assets', 'airshipvoyage', 'textures')

# материал: (контур, тень, основной, свет)
MAT = {
    'wood':   ('#3b2410', '#6b4422', '#946232', '#bb8649'),
    'plank':  ('#3b2410', '#7a5530', '#a2774a', '#c49a64'),
    'brass':  ('#4d3a0e', '#8f6c1e', '#c79b32', '#f0d06a'),
    'steel':  ('#2c3138', '#5e6772', '#8d97a3', '#c3cbd4'),
    'dural':  ('#3a3f47', '#7d8590', '#aab2bc', '#dde3e9'),
    'iron':   ('#202327', '#3e444b', '#585f68', '#7b838d'),
    'olive':  ('#1f2a17', '#3e4f2c', '#5a6e3e', '#7d9256'),
    'fabric': ('#4a4a44', '#9a9a8e', '#c4c4b6', '#e9e9dc'),
    'skin':   ('#4f4128', '#a08a55', '#cdb57a', '#ecdca4'),
    'rope':   ('#3a2a14', '#6e5228', '#957038', '#b8925a'),
    'blue':   ('#13284f', '#234a8a', '#2f5fae', '#4a7cc8'),
    'h2':     ('#0e4050', '#1f7f98', '#3cb6d2', '#8ce4f2'),
    'he':     ('#3a1650', '#6c2f8f', '#9b52c4', '#cf9af0'),
    'red':    ('#3d0d0d', '#7a1d1d', '#a83232', '#d25a4a'),
    'dark':   ('#121417', '#24282d', '#33383f', '#4a5059'),
}


class Sprite:
    def __init__(self, n=16):
        self.n = n
        self.mat = [[None] * n for _ in range(n)]
        self.over = {}  # (x, y) -> цвет поверх (детали)

    def put(self, x, y, m):
        if 0 <= x < self.n and 0 <= y < self.n:
            self.mat[y][x] = m

    def fill(self, m, pred):
        for y in range(self.n):
            for x in range(self.n):
                if pred(x + 0.5, y + 0.5):
                    self.mat[y][x] = m

    def rect(self, m, x0, y0, x1, y1):
        for y in range(y0, y1 + 1):
            for x in range(x0, x1 + 1):
                self.put(x, y, m)

    def line(self, m, x0, y0, x1, y1, w=1.0):
        def d(px, py):
            vx, vy = x1 - x0, y1 - y0
            t = max(0, min(1, ((px - x0) * vx + (py - y0) * vy) / (vx * vx + vy * vy)))
            return math.hypot(px - x0 - t * vx, py - y0 - t * vy)
        self.fill(m, lambda px, py: d(px, py) <= w / 2)

    def clear(self, x, y):
        self.put(x, y, None)

    def dot(self, x, y, color):
        self.over[(x, y)] = color

    def render_outside(self, colors, outline_color):
        """Тонкая конструкция: пиксели — точными тонами по материалу ({материал: тон}), контур — вокруг снаружи."""
        n = self.n
        img = Image.new('RGBA', (n, n), (0, 0, 0, 0))
        for y in range(n):
            for x in range(n):
                m = self.mat[y][x]
                if m is not None:
                    img.putpixel((x, y), hex_rgba(colors[m]))
                    continue
                if any(0 <= x + dx < n and 0 <= y + dy < n and self.mat[y + dy][x + dx] is not None
                       for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1))):
                    img.putpixel((x, y), hex_rgba(outline_color))
        for (x, y), c in self.over.items():
            img.putpixel((x, y), hex_rgba(c) if c else (0, 0, 0, 0))
        return img

    def render(self, outline=True, shade=True, inner_edges=False):
        n = self.n
        img = Image.new('RGBA', (n, n), (0, 0, 0, 0))
        filled = lambda x, y: 0 <= x < n and 0 <= y < n and self.mat[y][x] is not None
        same = lambda x, y, m: 0 <= x < n and 0 <= y < n and self.mat[y][x] == m
        for y in range(n):
            for x in range(n):
                m = self.mat[y][x]
                if m is None:
                    continue
                tones = MAT[m]
                nb = [(x - 1, y), (x + 1, y), (x, y - 1), (x, y + 1)]
                edge = any(not filled(a, b) for a, b in nb)
                if inner_edges:
                    edge = edge or any(filled(a, b) and not same(a, b, m) for a, b in nb[1::2])
                if outline and edge:
                    c = tones[0]
                elif shade:
                    up_left = not same(x - 1, y, m) or not same(x, y - 1, m)
                    down_right = not same(x + 1, y, m) or not same(x, y + 1, m)
                    c = tones[3] if up_left and not down_right else tones[1] if down_right and not up_left else tones[2]
                else:
                    c = tones[2]
                img.putpixel((x, y), hex_rgba(c))
        for (x, y), c in self.over.items():
            img.putpixel((x, y), hex_rgba(c) if c else (0, 0, 0, 0))
        return img


def hex_rgba(c):
    c = c.lstrip('#')
    return (int(c[0:2], 16), int(c[2:4], 16), int(c[4:6], 16), 255)


# ---------------------------------------------------------------------------------------------- спрайты

def helm():
    s = Sprite()
    cx = cy = 8.0
    for k in range(8):  # спицы с рукоятками за ободом
        a = k * math.pi / 4
        s.line('wood', cx + 1.5 * math.cos(a), cy + 1.5 * math.sin(a), cx + 7.3 * math.cos(a), cy + 7.3 * math.sin(a), 1.3)
    s.fill('wood', lambda x, y: 3.6 <= math.hypot(x - cx, y - cy) <= 6.0)
    s.fill('brass', lambda x, y: math.hypot(x - cx, y - cy) <= 2.0)
    img = s.render()
    # рукоятки — светлее, ступица — блик
    for k in range(8):
        a = k * math.pi / 4
        x, y = int(cx + 6.9 * math.cos(a)), int(cy + 6.9 * math.sin(a))
        if 0 <= x < 16 and 0 <= y < 16 and img.getpixel((x, y))[3]:
            img.putpixel((x, y), hex_rgba(MAT['wood'][3]))
    img.putpixel((7, 7), hex_rgba(MAT['brass'][3]))
    img.putpixel((8, 8), hex_rgba(MAT['brass'][1]))
    return img


def blueprint():
    s = Sprite()
    s.rect('blue', 1, 2, 14, 13)
    s.clear(14, 2)
    img = s.render()
    w, l = hex_rgba('#e6efff'), hex_rgba('#7fa6e0')
    for x in range(2, 14):  # сетка
        for y in (5, 10):
            img.putpixel((x, y), hex_rgba(MAT['blue'][3]))
    for y in range(3, 13):
        for x in (4, 11):
            img.putpixel((x, y), hex_rgba(MAT['blue'][3]))
    # силуэт дирижабля белым: корпус-сигара, оперение, гондола
    pts = [(5, 6), (6, 6), (7, 6), (8, 6), (9, 6), (10, 6), (4, 7), (11, 7), (3, 8), (12, 8), (4, 9), (11, 9),
           (5, 10), (6, 10), (7, 10), (8, 10), (9, 10), (10, 10), (2, 6), (3, 7), (2, 10), (3, 9), (7, 11), (8, 11)]
    for p in pts:
        img.putpixel(p, w)
    for p in [(5, 8), (6, 8), (7, 8), (8, 8), (9, 8), (10, 8)]:
        img.putpixel(p, l)  # осевая линия
    img.putpixel((13, 2), hex_rgba('#dfe9ff'))  # загнутый уголок
    img.putpixel((14, 3), hex_rgba('#9db8e0'))
    return img


def truss():
    s = Sprite()
    # два пояса и поперечины-«лестница» с раскосами: дюралевая ферма по диагонали
    s.fill('a', lambda x, y: abs(x + y - 13) <= 0.75 and 2 <= x <= 12)
    s.fill('b', lambda x, y: abs(x + y - 18) <= 0.75 and 5 <= x <= 15)
    for a in range(3, 13, 3):
        for k in range(1, 3):
            s.put(a + k, 12 - a + k, 'c')
    for a in range(3, 12, 3):  # раскосы
        s.put(a + 2, 12 - a - 1 + 2 - 1, 'c')
    return s.render_outside({'a': MAT['dural'][3], 'b': MAT['dural'][2], 'c': MAT['dural'][1]}, MAT['dural'][0])


def ring():
    s = Sprite()
    cx, cy = 15.5, 15.5
    r = lambda x, y: math.hypot(x - cx, y - cy)
    inside = lambda x, y: x < 15 and y < 15
    s.fill('a', lambda x, y: inside(x, y) and abs(r(x, y) - 12.6) <= 0.62)
    s.fill('b', lambda x, y: inside(x, y) and abs(r(x, y) - 9.0) <= 0.62)
    for deg in range(185, 270, 14):  # поперечины между поясами
        a = math.radians(deg)
        for rr in (10.0, 11.0, 11.8):
            x, y = int(cx + rr * math.cos(a)), int(cy + rr * math.sin(a))
            if s.mat[y][x] is None:
                s.put(x, y, 'c')
    return s.render_outside({'a': MAT['dural'][3], 'b': MAT['dural'][2], 'c': MAT['dural'][1]}, MAT['dural'][0])


def keel():
    s = Sprite()
    # балка-треугольник под настилом (дюраль), настил досками сверху
    s.line('dural', 3.5, 14.5, 14.5, 3.5, 2.2)
    s.line('plank', 1.0, 11.0, 11.0, 1.0, 4.4)
    img = s.render(inner_edges=True)
    for i in range(1, 10, 2):  # стыки досок
        img.putpixel((i + 1, 11 - i), hex_rgba(MAT['plank'][1]))
        img.putpixel((i + 2, 12 - i), hex_rgba(MAT['plank'][1]))
    for i in range(4, 14, 3):  # окна балки
        img.putpixel((i, 17 - i), hex_rgba(MAT['dural'][0]))
    return img


def engine():
    s = Sprite()
    s.line('steel', 8.5, 1.0, 8.5, 6.0, 1.2)       # подвеска к корпусу
    s.line('steel', 12.5, 1.0, 11.5, 6.0, 1.2)
    s.fill('olive', lambda x, y: ((x - 9.0) / 5.6) ** 2 + ((y - 9.0) / 3.1) ** 2 <= 1)  # гондола
    s.rect('iron', 3, 7, 4, 10)                     # радиатор спереди
    s.line('wood', 1.6, 1.8, 1.6, 15.0, 1.8)        # винт
    s.rect('dark', 1, 8, 2, 9)                      # ступица
    img = s.render()
    for x in (8, 11):                               # окна
        img.putpixel((x, 8), hex_rgba('#a9d6e8'))
        img.putpixel((x, 9), hex_rgba('#5d8ea3'))
    for x in range(6, 14):
        img.putpixel((x, 11), hex_rgba(MAT['olive'][1]))
    img.putpixel((14, 7), hex_rgba('#2a2a2a'))       # выхлоп
    return img


def fin():
    s = Sprite()
    # стабилизатор (обшивка) и руль за шарниром
    s.fill('fabric', lambda x, y: 2 <= y <= 14 and x >= 1 + (14 - y) * 0.55 and x <= 10)
    s.fill('red', lambda x, y: 4 <= y <= 14 and 10.5 <= x <= 14.5)
    img = s.render(inner_edges=True)
    for y in range(4, 14):
        for x in (5, 8):
            p = img.getpixel((x, y))
            if p[3] and p[:3] == hex_rgba(MAT['fabric'][2])[:3]:
                img.putpixel((x, y), hex_rgba(MAT['fabric'][1]))  # нервюры
    for y in range(5, 14):
        img.putpixel((12, y), hex_rgba('#e6e0d0') if y % 4 else hex_rgba(MAT['red'][1]))  # белая полоса руля
    img.putpixel((11, 3), hex_rgba(MAT['steel'][0]))  # кабанчик руля
    img.putpixel((11, 2), hex_rgba(MAT['steel'][2]))
    return img


def gas_cell():
    s = Sprite()
    # сложенная оболочка: стопка складок, перевязанная верёвкой крест-накрест
    s.fill('skin', lambda x, y: 2 <= x <= 14 and 4 <= y <= 13 and not ((x < 3 or x > 13) and (y < 5 or y > 12)))
    img = s.render()
    for y in (6, 8, 10, 12):
        for x in range(3, 14):
            img.putpixel((x, y), hex_rgba(MAT['skin'][1]))
        for x in range(3, 14):
            if img.getpixel((x, y - 1))[:3] == hex_rgba(MAT['skin'][2])[:3]:
                img.putpixel((x, y - 1), hex_rgba(MAT['skin'][3]) if x % 3 else hex_rgba(MAT['skin'][2]))
    rope_d, rope_l = hex_rgba(MAT['rope'][0]), hex_rgba(MAT['rope'][3])
    for y in range(3, 15):
        img.putpixel((8, y), rope_l if y % 2 else hex_rgba(MAT['rope'][2]))
        img.putpixel((9, y), rope_d)
    for x in range(1, 16):
        img.putpixel((x, 9), rope_l if x % 2 else hex_rgba(MAT['rope'][2]))
    img.putpixel((8, 9), rope_d)
    img.putpixel((9, 2), rope_l)  # хвост узла
    img.putpixel((10, 2), rope_d)
    return img


def gas_supply(gas):
    s = Sprite()
    for x0 in (2, 9):  # два баллона
        s.fill('steel', lambda x, y, x0=x0: x0 <= x <= x0 + 5 and (5.5 <= y <= 15.5 or
                                                                 math.hypot(x - x0 - 2.5, y - 5.6) <= 2.6))
    img = s.render()
    g = MAT[gas]
    for x0 in (2, 9):
        for x in range(x0 + 1, x0 + 4 + 1):
            img.putpixel((x, 4), hex_rgba(g[2]))           # купол цвета газа
            img.putpixel((x, 5), hex_rgba(g[1]))
            img.putpixel((x, 10), hex_rgba(g[3]))          # полоса
            img.putpixel((x, 11), hex_rgba(g[2]))
        img.putpixel((x0 + 1, 7), hex_rgba(MAT['steel'][3]))  # блик
        img.putpixel((x0 + 1, 8), hex_rgba(MAT['steel'][3]))
        img.putpixel((x0 + 1, 13), hex_rgba(MAT['steel'][3]))
        for (dx, dy, t) in ((2, 1, 3), (3, 1, 2), (2, 2, 1), (3, 2, 1)):  # латунный вентиль
            img.putpixel((x0 + dx, dy), hex_rgba(MAT['brass'][t]))
        img.putpixel((x0 + 1, 1), hex_rgba(MAT['brass'][0]))
        img.putpixel((x0 + 4, 1), hex_rgba(MAT['brass'][0]))
    for x in range(1, 16):                                  # железные хомуты рамы
        for y in (7, 13):
            img.putpixel((x, y), hex_rgba(MAT['iron'][2] if x not in (1, 15) else MAT['iron'][0]))
    return img


def table_top():
    img = Image.new('RGBA', (16, 16), hex_rgba('#4a2f19'))
    for y in range(16):
        for x in range(16):
            if (x + 3 * (y // 4)) % 7 == 0:
                img.putpixel((x, y), hex_rgba('#3e2614'))
            if x in (0, 15) or y in (0, 15):
                img.putpixel((x, y), hex_rgba('#2c1a0c'))
    bp = blueprint()
    for y in range(2, 14):
        for x in range(1, 15):
            p = bp.getpixel((x, y))
            if p[3]:
                img.putpixel((x, y), p)
    for i in range(5):  # карандаш
        img.putpixel((10 + i, 15 - i - 1), hex_rgba('#e0b84a') if i < 4 else hex_rgba('#2a2a2a'))
    for x in range(2, 9):  # линейка
        img.putpixel((x, 14), hex_rgba('#d9c9a0') if x % 2 else hex_rgba('#8a7a55'))
    return img


def table_side():
    img = Image.new('RGBA', (16, 16), hex_rgba('#4a2f19'))
    for y in range(16):
        for x in range(16):
            c = '#4a2f19'
            if y in (4, 9, 14):
                c = '#3a2412'
            if (x + 5 * (y // 5)) % 9 == 0:
                c = '#3e2614'
            if y <= 1:
                c = '#5e3d22' if y == 0 else '#2c1a0c'
            img.putpixel((x, y), hex_rgba(c))
    for x in range(3, 13):  # ящик
        img.putpixel((x, 5), hex_rgba('#2c1a0c'))
        img.putpixel((x, 9), hex_rgba('#2c1a0c'))
    for y in range(5, 10):
        img.putpixel((3, y), hex_rgba('#2c1a0c'))
        img.putpixel((12, y), hex_rgba('#2c1a0c'))
    for x in range(4, 12):
        for y in range(6, 9):
            img.putpixel((x, y), hex_rgba('#5a3a20'))
    img.putpixel((7, 7), hex_rgba(MAT['brass'][3]))
    img.putpixel((8, 7), hex_rgba(MAT['brass'][2]))
    for y in range(10, 16):  # ножки
        for x in (0, 1, 14, 15):
            img.putpixel((x, y), hex_rgba('#2c1a0c' if x in (0, 15) else '#3a2412'))
    for y in range(11, 16):
        for x in range(2, 14):
            img.putpixel((x, y), (0, 0, 0, 0))
    return img


ITEMS = {
    'helm': helm, 'prototype_blueprint': blueprint, 'truss_section': truss, 'ring_segment': ring,
    'keel_section': keel, 'engine_pod': engine, 'tail_fin': fin, 'folded_gas_cell': gas_cell,
    'hydrogen_supply': lambda: gas_supply('h2'), 'helium_supply': lambda: gas_supply('he'),
}
BLOCKS = {'blueprint_table_top': table_top, 'blueprint_table_side': table_side}


def main():
    out = {}
    for name, fn in ITEMS.items():
        out[('item', name)] = fn()
    for name, fn in BLOCKS.items():
        out[('block', name)] = fn()
    for (kind, name), img in out.items():
        d = os.path.join(ASSETS, kind)
        os.makedirs(d, exist_ok=True)
        img.save(os.path.join(d, name + '.png'))
    if '--preview' in sys.argv:
        scale, pad = 12, 8
        sheet = Image.new('RGBA', (len(out) * (16 * scale + pad) + pad, 16 * scale + 2 * pad), (139, 139, 139, 255))
        for i, img in enumerate(out.values()):
            sheet.alpha_composite(img.resize((16 * scale, 16 * scale), Image.NEAREST), (pad + i * (16 * scale + pad), pad))
        os.makedirs(os.path.join(ROOT, 'build'), exist_ok=True)
        sheet.save(os.path.join(ROOT, 'build', 'texture_preview.png'))
    print('written', len(out))


if __name__ == '__main__':
    main()
