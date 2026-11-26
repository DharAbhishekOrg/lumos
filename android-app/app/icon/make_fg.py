# Lumos - android-app/app / Generates the adaptive launcher icon foreground at every density.
# Author: dhar.abhishek.email@gmail.com
# Notes: The iPhone mark (ios-app/app/icon/make_icon.py) on transparency, kept
# inside the 66/108 safe zone; the background layer is drawable/ic_launcher_background.xml.
# Run: python3 -I android-app/app/icon/make_fg.py android-app/app/src/main/res
import sys, numpy as np
from PIL import Image
out_dir = sys.argv[1]
for d, px in [('mdpi',108),('hdpi',162),('xhdpi',216),('xxhdpi',324),('xxxhdpi',432)]:
    y, x = np.mgrid[0:px, 0:px].astype(np.float64)
    r = np.hypot(x - px/2, y - px/2) / px          # 0 at centre, 0.5 at the edge
    alpha = np.zeros((px, px))
    color = np.zeros((px, px, 3))
    def add(a, rgb):
        global alpha, color
        a = np.clip(a, 0, 1)
        color = color * (1 - a[..., None]) + np.array(rgb) * a[..., None]
        alpha = alpha + a * (1 - alpha)
    add(np.exp(-(r / 0.14) ** 2) * 0.55, [255, 170, 90])             # halo
    for radius, strength in ((0.12, 0.9), (0.19, 0.6), (0.26, 0.35)):  # rings, inside r<=0.305
        add(np.exp(-((r - radius) / 0.006) ** 2) * strength, [255, 214, 160])
    add(np.exp(-(r / 0.04) ** 2), [255, 246, 228])                     # core
    add(np.exp(-(r / 0.016) ** 2), [255, 255, 255])                    # spark
    rgba = np.dstack([np.clip(color, 0, 255), np.clip(alpha * 255, 0, 255)]).astype(np.uint8)
    import os; os.makedirs(f'{out_dir}/mipmap-{d}', exist_ok=True)
    Image.fromarray(rgba, 'RGBA').save(f'{out_dir}/mipmap-{d}/ic_launcher_foreground.png', optimize=True)
print('ok')
