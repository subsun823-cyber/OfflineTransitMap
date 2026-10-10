#!/usr/bin/env python3
"""Generate all combination station icons for OfflineTransitMap."""
import os
from PySide6.QtGui import QImage, QPainter, QColor, QPainterPath, QBrush
from PySide6.QtCore import Qt, QRectF

DRAWABLE_DIR = 'app/src/main/res/drawable-xxxhdpi'
os.makedirs(DRAWABLE_DIR, exist_ok=True)

# 1. Ensure map_station_toei.png exists
toei_path = os.path.join(DRAWABLE_DIR, 'map_station_toei.png')
toei_img = QImage(80, 80, QImage.Format_ARGB32)
toei_img.fill(QColor(0, 0, 0, 0))
painter = QPainter(toei_img)
painter.setRenderHint(QPainter.Antialiasing, True)
painter.setRenderHint(QPainter.SmoothPixmapTransform, True)

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

# 2. Ensure map_station_seibu.png exists
seibu_path = os.path.join(DRAWABLE_DIR, 'map_station_seibu.png')
src_seibu_logo = 'ダウンロード.png'
if os.path.exists(src_seibu_logo):
    src_img = QImage(src_seibu_logo).convertToFormat(QImage.Format_ARGB32)
    seibu_img = QImage(80, 80, QImage.Format_ARGB32)
    seibu_img.fill(QColor(0, 0, 0, 0))
    p = QPainter(seibu_img)
    p.setRenderHint(QPainter.Antialiasing, True)
    p.setRenderHint(QPainter.SmoothPixmapTransform, True)

    circle_path = QPainterPath()
    circle_path.addEllipse(QRectF(1.0, 1.0, 78.0, 78.0))
    p.setClipPath(circle_path)
    p.fillPath(circle_path, QBrush(QColor('#ffffff')))

    margin = 6.0
    p.drawImage(QRectF(margin, margin, 80.0 - 2 * margin, 80.0 - 2 * margin), src_img)
    p.end()
    seibu_img.save(seibu_path)
    print(f'Saved {seibu_path}')
else:
    seibu_img = QImage(seibu_path)

# 3. Ensure map_station_tobu.png exists (Tobu Railway Future Blue "T" badge matching Google Maps)
tobu_path = os.path.join(DRAWABLE_DIR, 'map_station_tobu.png')
tobu_img = QImage(80, 80, QImage.Format_ARGB32)
tobu_img.fill(QColor(0, 0, 0, 0))
p_t = QPainter(tobu_img)
p_t.setRenderHint(QPainter.Antialiasing, True)
p_t.setRenderHint(QPainter.SmoothPixmapTransform, True)

# White rounded rectangle
s = 80.0 / 90.0
rect_path = QPainterPath()
rect_path.addRoundedRect(QRectF(11.0 * s, 11.0 * s, 68.0 * s, 68.0 * s), 16.0 * s, 16.0 * s)
p_t.fillPath(rect_path, QBrush(QColor('#ffffff')))

# Vector shapes for Tobu symbol matching Google Maps exact geometry
tobu_blue = QColor('#005BAA')
p_t.setBrush(QBrush(tobu_blue))
p_t.setPen(Qt.NoPen)

def pt(x, y):
    return x * s, y * s

# T symbol (crossbar + italic stem)
p_t_body = QPainterPath()
p_t_body.moveTo(*pt(31.5, 34.5))
p_t_body.lineTo(*pt(62.5, 34.5))
p_t_body.lineTo(*pt(60.8, 38.2))
p_t_body.lineTo(*pt(47.2, 38.2))
p_t_body.lineTo(*pt(36.0, 63.5))
p_t_body.lineTo(*pt(29.5, 63.5))
p_t_body.lineTo(*pt(40.7, 38.2))
p_t_body.lineTo(*pt(29.8, 38.2))
p_t_body.closeSubpath()
p_t.fillPath(p_t_body, QBrush(tobu_blue))

# Top-left diagonal bar
p_tl = QPainterPath()
p_tl.moveTo(*pt(49.0, 15.0))
p_tl.lineTo(*pt(45.0, 26.5))
p_tl.lineTo(*pt(42.0, 26.5))
p_tl.lineTo(*pt(46.0, 15.0))
p_tl.closeSubpath()
p_t.fillPath(p_tl, QBrush(tobu_blue))

# Bottom-right diagonal bar
p_br = QPainterPath()
p_br.moveTo(*pt(58.0, 46.5))
p_br.lineTo(*pt(45.5, 75.5))
p_br.lineTo(*pt(49.2, 75.5))
p_br.lineTo(*pt(61.8, 46.5))
p_br.closeSubpath()
p_t.fillPath(p_br, QBrush(tobu_blue))

# Left horizontal bar
p_lh = QPainterPath()
p_lh.addRect(QRectF(*pt(14.5, 45.5), 13.0 * s, 3.8 * s))
p_t.fillPath(p_lh, QBrush(tobu_blue))

# Right horizontal bar
p_rh = QPainterPath()
p_rh.addRect(QRectF(*pt(65.5, 23.5), 10.5 * s, 3.8 * s))
p_t.fillPath(p_rh, QBrush(tobu_blue))

p_t.end()
tobu_img.save(tobu_path)
print(f'Saved {tobu_path}')

# Load base 80x80 icons
icons = {
    'jr': QImage(os.path.join(DRAWABLE_DIR, 'map_station_jr_east.png')),
    'private': QImage(os.path.join(DRAWABLE_DIR, 'map_station_rail.png')),
    'metro': QImage(os.path.join(DRAWABLE_DIR, 'map_station_tokyo_metro.png')),
    'toei': toei_img,
    'seibu': seibu_img,
    'tobu': tobu_img,
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
    # 2-operator combos (generic private)
    'map_station_rail_both.png': ['jr', 'private'],
    'map_station_rail_jr_metro.png': ['jr', 'metro'],
    'map_station_rail_jr_toei.png': ['jr', 'toei'],
    'map_station_rail_private_metro.png': ['private', 'metro'],
    'map_station_rail_private_toei.png': ['private', 'toei'],
    'map_station_rail_metro_toei.png': ['metro', 'toei'],

    # 3-operator combos (generic private)
    'map_station_rail_jr_private_metro.png': ['jr', 'private', 'metro'],
    'map_station_rail_jr_private_toei.png': ['jr', 'private', 'toei'],
    'map_station_rail_jr_metro_toei.png': ['jr', 'metro', 'toei'],
    'map_station_rail_private_metro_toei.png': ['private', 'metro', 'toei'],

    # 4-operator combos (generic private)
    'map_station_rail_jr_private_metro_toei.png': ['jr', 'private', 'metro', 'toei'],

    # Seibu combos (2-operator)
    'map_station_rail_jr_seibu.png': ['jr', 'seibu'],
    'map_station_rail_seibu_metro.png': ['seibu', 'metro'],
    'map_station_rail_seibu_toei.png': ['seibu', 'toei'],
    'map_station_rail_seibu_private.png': ['seibu', 'private'],

    # Seibu combos (3-operator)
    'map_station_rail_jr_seibu_metro.png': ['jr', 'seibu', 'metro'],
    'map_station_rail_jr_seibu_toei.png': ['jr', 'seibu', 'toei'],
    'map_station_rail_seibu_metro_toei.png': ['seibu', 'metro', 'toei'],

    # Seibu combos (4-operator)
    'map_station_rail_jr_seibu_metro_toei.png': ['jr', 'seibu', 'metro', 'toei'],

    # Tobu combos (2-operator)
    'map_station_rail_jr_tobu.png': ['jr', 'tobu'],
    'map_station_rail_tobu_metro.png': ['tobu', 'metro'],
    'map_station_rail_tobu_toei.png': ['tobu', 'toei'],
    'map_station_rail_tobu_private.png': ['tobu', 'private'],
    'map_station_rail_tobu_seibu.png': ['seibu', 'tobu'],

    # Tobu combos (3-operator)
    'map_station_rail_jr_tobu_metro.png': ['jr', 'tobu', 'metro'],
    'map_station_rail_jr_tobu_toei.png': ['jr', 'tobu', 'toei'],
    'map_station_rail_tobu_metro_toei.png': ['tobu', 'metro', 'toei'],
    'map_station_rail_jr_tobu_seibu.png': ['jr', 'seibu', 'tobu'],

    # Tobu combos (4-operator)
    'map_station_rail_jr_tobu_metro_toei.png': ['jr', 'tobu', 'metro', 'toei'],
    'map_station_rail_jr_seibu_tobu_metro.png': ['jr', 'seibu', 'tobu', 'metro'],
}

for fname, keys in combinations.items():
    out_path = os.path.join(DRAWABLE_DIR, fname)
    combined = create_combined(keys)
    combined.save(out_path)
    print(f'Generated {out_path} ({combined.width()}x{combined.height()}) for {keys}')

print('All combination station icons generated successfully.')
