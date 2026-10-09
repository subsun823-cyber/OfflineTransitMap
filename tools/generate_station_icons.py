#!/usr/bin/env python3
"""Generate all combination station icons for OfflineTransitMap."""
import os
from PySide6.QtGui import QImage, QPainter, QColor, QPainterPath
from PySide6.QtCore import Qt

DRAWABLE_DIR = 'app/src/main/res/drawable-xxxhdpi'
os.makedirs(DRAWABLE_DIR, exist_ok=True)

# 1. Ensure map_station_toei.png exists
toei_path = os.path.join(DRAWABLE_DIR, 'map_station_toei.png')
toei_img = QImage(80, 80, QImage.Format_ARGB32)
toei_img.fill(QColor(0, 0, 0, 0))
painter = QPainter(toei_img)
painter.setRenderHint(QPainter.Antialiasing, True)
painter.setRenderHint(QPainter.SmoothPixmapTransform, True)

# Path definition of Tokyo ginkgo leaf from SVG (500x500 scaled to 70x70)
path = QPainterPath()
s = 70.0 / 500.0
ox = 5.0
oy = 5.0

path.moveTo(ox + 250*s, oy + 0*s)
path.cubicTo(ox + 112*s, oy + 0*s, ox + 0*s, oy + 112*s, ox + 0*s, oy + 250*s)
path.cubicTo(ox + 0*s, oy + 250.062*s, ox + 134.49*s, oy + 254.07*s, ox + 242.5*s, oy + 499.875*s)
path.cubicTo(ox + 245*s, oy + 500*s, ox + 247.49*s, oy + 500*s, ox + 250*s, oy + 500*s)
path.cubicTo(ox + 252.51*s, oy + 500*s, ox + 255*s, oy + 500*s, ox + 257.5*s, oy + 500*s)
path.cubicTo(ox + 257.57*s, oy + 364.45*s, ox + 365.51*s, oy + 254.07*s, ox + 500*s, oy + 250.09*s)
path.cubicTo(ox + 500*s, oy + 250.03*s, ox + 500*s, oy + 250*s, ox + 500*s, oy + 250*s)
path.cubicTo(ox + 500*s, oy + 112*s, ox + 388*s, oy + 0*s, ox + 250*s, oy + 0*s)
path.closeSubpath()

painter.setPen(Qt.NoPen)
painter.setBrush(QColor('#199332'))
painter.drawPath(path)
painter.end()
toei_img.save(toei_path)
print(f'Saved {toei_path}')

# Load base 80x80 icons
icons = {
    'jr': QImage(os.path.join(DRAWABLE_DIR, 'map_station_jr_east.png')),
    'private': QImage(os.path.join(DRAWABLE_DIR, 'map_station_rail.png')),
    'metro': QImage(os.path.join(DRAWABLE_DIR, 'map_station_tokyo_metro.png')),
    'toei': toei_img,
}

def create_combined(icon_keys):
    gap = 8
    n = len(icon_keys)
    w = 80 * n + gap * (n - 1)
    h = 80
    res = QImage(w, h, QImage.Format_ARGB32)
    res.fill(QColor(0, 0, 0, 0))
    p = QPainter(res)
    p.setRenderHint(QPainter.Antialiasing, True)
    p.setRenderHint(QPainter.SmoothPixmapTransform, True)
    x = 0
    for key in icon_keys:
        p.drawImage(x, 0, icons[key])
        x += 80 + gap
    p.end()
    return res

combinations = {
    # 2-operator combos
    'map_station_rail_both.png': ['jr', 'private'], # keep existing or regenerate for consistency
    'map_station_rail_jr_metro.png': ['jr', 'metro'],
    'map_station_rail_jr_toei.png': ['jr', 'toei'],
    'map_station_rail_private_metro.png': ['private', 'metro'],
    'map_station_rail_private_toei.png': ['private', 'toei'],
    'map_station_rail_metro_toei.png': ['metro', 'toei'],
    # 3-operator combos
    'map_station_rail_jr_private_metro.png': ['jr', 'private', 'metro'],
    'map_station_rail_jr_private_toei.png': ['jr', 'private', 'toei'],
    'map_station_rail_jr_metro_toei.png': ['jr', 'metro', 'toei'],
    'map_station_rail_private_metro_toei.png': ['private', 'metro', 'toei'],
    # 4-operator combos
    'map_station_rail_jr_private_metro_toei.png': ['jr', 'private', 'metro', 'toei'],
}

for fname, keys in combinations.items():
    out_path = os.path.join(DRAWABLE_DIR, fname)
    combined = create_combined(keys)
    combined.save(out_path)
    print(f'Generated {out_path} ({combined.width()}x{combined.height()}) for {keys}')

print('All combination station icons generated successfully.')
