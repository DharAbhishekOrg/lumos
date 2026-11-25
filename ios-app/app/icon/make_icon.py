# Lumos - ios-app/app / Generates the app icon (AppIcon.png, 1024x1024, opaque).
# Author: dhar.abhishek.email@gmail.com
# Notes: An original mark, drawn in code so it can be regenerated and reviewed:
# a warm point of light with soft "nearby" rings on a night gradient. A
# placeholder until a designed icon replaces it. Run:
#   python3 -I icon/make_icon.py Lumos/Assets.xcassets/AppIcon.appiconset/AppIcon.png
import numpy as np
from PIL import Image
N = 1024
y, x = np.mgrid[0:N, 0:N].astype(np.float64)
cx, cy = N * 0.5, N * 0.54
r = np.hypot(x - cx, y - cy) / N
# background: deep indigo at the top, violet toward the bottom
t = (y / N)[..., None]
top = np.array([18, 16, 52.]); bottom = np.array([74, 32, 112.])
img = top * (1 - t) + bottom * t
# soft halo
halo = np.exp(-(r / 0.22) ** 2)[..., None]
img = img + halo * np.array([255, 170, 90.]) * 0.55
# rings: three soft bands, fading outward
for radius, strength in ((0.19, 0.55), (0.29, 0.35), (0.39, 0.2)):
    band = np.exp(-((r - radius) / 0.008) ** 2)[..., None]
    img = img + band * np.array([255, 214, 160.]) * strength
# core light
core = np.exp(-(r / 0.06) ** 2)[..., None]
img = img * (1 - core) + core * np.array([255, 246, 228.])
spark = np.exp(-(r / 0.025) ** 2)[..., None]
img = img * (1 - spark) + spark * np.array([255, 255, 255.])
out = Image.fromarray(np.clip(img, 0, 255).astype(np.uint8), "RGB")
out.save(__import__("sys").argv[1], optimize=True)
