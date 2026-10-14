# -*- coding: utf-8 -*-
"""生成「作业哨兵」应用图标 PNG（用 Pillow 绘制，不依赖 cairo）。

用法:
    python tools/make_icons.py

输出:
    app/icons/icon-192.png
    app/icons/icon-512.png
    android/app/src/main/res/mipmap-*/ic_launcher.png
"""
import math
import os

from PIL import Image, ImageDraw

SS = 4                      # 超采样倍数
BASE = 512                  # 逻辑画布尺寸
S = BASE * SS
WHITE = (255, 255, 255, 255)
INK = (79, 70, 229, 255)    # 勾选颜色，和信息色一致
AMBER = (251, 191, 36, 255)

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)


def rrect_mask(size, radius, ss=1):
    m = Image.new('L', (size * ss, size * ss), 0)
    d = ImageDraw.Draw(m)
    d.rounded_rectangle([0, 0, size * ss - 1, size * ss - 1], radius=radius * ss, fill=255)
    return m


def gradient_layer(size):
    """从左上到右下的靛蓝→紫渐变（先在低分辨率上算，再放大，避免纯 Python 逐像素太慢）。"""
    small = 768
    img = Image.new('RGBA', (small, small))
    px = img.load()
    c0 = (99, 102, 241)     # #6366F1
    c1 = (79, 70, 229)      # #4F46E5
    c2 = (124, 58, 237)     # #7C3AED
    denom = 2.0 * (small - 1)
    for y in range(small):
        for x in range(small):
            t = (x + y) / denom
            if t < 0.55:
                u = t / 0.55
                col = tuple(int(c0[i] + (c1[i] - c0[i]) * u) for i in range(3))
            else:
                u = (t - 0.55) / 0.45
                col = tuple(int(c1[i] + (c2[i] - c1[i]) * u) for i in range(3))
            px[x, y] = col + (255,)
    if size == small:
        return img
    return img.resize((size, size), Image.BILINEAR)


def build_icon(size):
    """绘制一枚图标，返回 RGBA Image。"""
    k = size * SS / float(BASE)     # 逻辑坐标 -> 绘制坐标

    def P(v):
        return v * k

    # --- 渐变底 ---
    canvas = gradient_layer(size * SS)

    # 右上角高光
    glow = Image.new('L', (size * SS, size * SS), 0)
    gd = ImageDraw.Draw(glow)
    gd.ellipse([P(224), P(-56), P(560), P(280)], fill=52)
    canvas = Image.composite(Image.new('RGBA', canvas.size, WHITE), canvas, glow)

    # --- 白色图形层（铃铛 + 铃锤）+ 遮罩 ---
    shape = Image.new('L', (size * SS, size * SS), 0)
    sd = ImageDraw.Draw(shape)

    # 铃身：圆顶 + 两侧竖直 + 底部外扩喇叭口
    cx, top, half = P(256), P(134), P(86)
    sd.pieslice([cx - half, top, cx + half, top + 2 * half], 180, 360, fill=255)
    sd.rectangle([cx - half, top + half, cx + half, P(380)], fill=255)
    sd.polygon([(cx - half, P(380)), (cx - P(136), P(430)),
                (cx + P(136), P(430)), (cx + half, P(380))], fill=255)

    # 铃锤：画在铃身下方，露出一截
    cd = P(37)
    sd.ellipse([cx - cd, P(434) - cd, cx + cd, P(434) + cd], fill=255)

    # 顶部提醒小圆点
    dot = P(24)
    sd.ellipse([cx - dot, P(84) - dot, cx + dot, P(84) + dot], fill=255)

    bell = Image.new('RGBA', canvas.size, WHITE)
    canvas = Image.composite(bell, canvas, shape)

    # --- 勾选（画在铃身上，先描一圈底再压主色，保证边缘干净）---
    pts = [(P(210), P(258)), (P(242), P(292)), (P(304), P(222))]
    overlay = Image.new('RGBA', canvas.size, (0, 0, 0, 0))
    od = ImageDraw.Draw(overlay)
    halo = int(P(44))
    for i in range(len(pts) - 1):
        od.line([pts[i], pts[i + 1]], fill=WHITE, width=halo, joint='curve')
    for pt in pts:
        r = halo / 2.0
        od.ellipse([pt[0] - r, pt[1] - r, pt[0] + r, pt[1] + r], fill=WHITE)
    for i in range(len(pts) - 1):
        od.line([pts[i], pts[i + 1]], fill=INK, width=int(P(27)), joint='curve')
    for pt in pts:
        r = P(13.5)
        od.ellipse([pt[0] - r, pt[1] - r, pt[0] + r, pt[1] + r], fill=INK)
    canvas = Image.alpha_composite(canvas, overlay)

    # --- 圆角裁切 ---
    mask = rrect_mask(size * SS, 118 * SS * size / float(BASE))
    out = Image.new('RGBA', canvas.size, (0, 0, 0, 0))
    out.paste(canvas, (0, 0), mask)

    return out.resize((size, size), Image.LANCZOS)


def save(img, path):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    img.save(path, 'PNG', optimize=True)
    print('  wrote %-64s %dx%d' % (os.path.relpath(path, ROOT), img.width, img.height))


def main():
    print('生成应用图标 ...')
    save(build_icon(512), os.path.join(ROOT, 'app', 'icons', 'icon-512.png'))
    save(build_icon(192), os.path.join(ROOT, 'app', 'icons', 'icon-192.png'))

    # 安卓启动图标
    android_sizes = {'mdpi': 48, 'hdpi': 72, 'xhdpi': 96, 'xxhdpi': 144, 'xxxhdpi': 192}
    for dpi, px in android_sizes.items():
        res = os.path.join(ROOT, 'android', 'app', 'src', 'main', 'res', 'mipmap-' + dpi)
        if os.path.isdir(os.path.dirname(res)):
            save(build_icon(px), os.path.join(res, 'ic_launcher.png'))
    print('完成。')


if __name__ == '__main__':
    main()
