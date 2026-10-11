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

# 4. Ensure map_station_tokyu.png exists (Tokyu Corporation symbol matching Google Maps)
tokyu_path = os.path.join(DRAWABLE_DIR, 'map_station_tokyu.png')
tokyu_img = QImage(80, 80, QImage.Format_ARGB32)
tokyu_img.fill(QColor(0, 0, 0, 0))
p_tk = QPainter(tokyu_img)
p_tk.setRenderHint(QPainter.Antialiasing, True)
p_tk.setRenderHint(QPainter.SmoothPixmapTransform, True)

# White rounded rectangle
rect_path_tk = QPainterPath()
rect_path_tk.addRoundedRect(QRectF(11.0 * s, 11.0 * s, 68.0 * s, 68.0 * s), 16.0 * s, 16.0 * s)
p_tk.fillPath(rect_path_tk, QBrush(QColor('#ffffff')))

# Tokyu red symbol mark from vector SVG
from PySide6.QtCore import QByteArray
from PySide6.QtSvg import QSvgRenderer

svg_xml = """<?xml version="1.0" encoding="UTF-8"?>
<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 48 300 195" width="300" height="195">
<path fill="#e60012" d="m 150,50.008008 c -47.04952,0 -85.190625,30.996735 -85.190625,69.233202 0,38.23647 38.141105,69.2332 85.190625,69.2332 47.04952,0 85.19062,-30.99673 85.19062,-69.2332 0,-38.236467 -38.1411,-69.233202 -85.19062,-69.233202 z M 78.690234,83.497266 c 0,0 33.114026,14.4 71.309766,14.4 38.19574,0 71.30977,-14.4 71.30977,-14.4 C 178.59823,103.73284 150,155.49306 150,187.68691 150,155.49306 121.40176,103.73284 78.690234,83.497266 Z m -33.539061,36.478124 -1.334181,0.0393 c 0,0 -0.524196,84.19533 106.183008,86.49316 106.7072,-2.29783 106.18301,-86.49316 106.18301,-86.49316 l -1.33418,-0.0393 c 0,0 -5.49139,75.47025 -104.84883,75.69492 C 50.642562,195.44564 45.151173,119.97539 45.151173,119.97539 Z M 1.3271484,120.24551 0,120.2918 c 0,0 2.004381,116.73248 150,121.39511 C 297.99561,237.02428 300,120.2918 300,120.2918 l -1.32715,-0.0463 c 0,0 -10.10741,106.68811 -148.67285,110.60449 C 11.434568,226.93362 1.3271484,120.24551 1.3271484,120.24551 Z m 22.0441406,0 -1.327148,0.0463 c 0,0 4.302422,101.22002 127.955859,103.68281 C 273.65343,221.51182 277.95586,120.2918 277.95586,120.2918 l -1.32715,-0.0463 c 0,0 -6.79269,89.91341 -126.62871,92.89218 C 30.163968,210.15892 23.371289,120.24551 23.371289,120.24551 Z" />
</svg>"""

renderer = QSvgRenderer(QByteArray(svg_xml.encode('utf-8')))
target_rect = QRectF(14.0, 19.0, 52.0, 42.0)
renderer.render(p_tk, target_rect)
p_tk.end()
tokyu_img.save(tokyu_path)
print(f'Saved {tokyu_path}')

# Load base 80x80 icons
icons = {
    'jr': QImage(os.path.join(DRAWABLE_DIR, 'map_station_jr_east.png')),
    'private': QImage(os.path.join(DRAWABLE_DIR, 'map_station_rail.png')),
    'metro': QImage(os.path.join(DRAWABLE_DIR, 'map_station_tokyo_metro.png')),
    'toei': toei_img,
    'seibu': seibu_img,
    'tobu': tobu_img,
    'tokyu': tokyu_img,
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

    # Tokyu combos (2-operator)
    'map_station_rail_jr_tokyu.png': ['jr', 'tokyu'],
    'map_station_rail_tokyu_metro.png': ['tokyu', 'metro'],
    'map_station_rail_tokyu_toei.png': ['tokyu', 'toei'],
    'map_station_rail_tokyu_private.png': ['tokyu', 'private'],
    'map_station_rail_tokyu_seibu.png': ['seibu', 'tokyu'],
    'map_station_rail_tokyu_tobu.png': ['tobu', 'tokyu'],

    # Tokyu combos (3-operator)
    'map_station_rail_jr_tokyu_metro.png': ['jr', 'tokyu', 'metro'],
    'map_station_rail_jr_tokyu_toei.png': ['jr', 'tokyu', 'toei'],
    'map_station_rail_tokyu_metro_toei.png': ['tokyu', 'metro', 'toei'],
    'map_station_rail_jr_tokyu_private.png': ['jr', 'tokyu', 'private'],
    'map_station_rail_tokyu_private_metro.png': ['tokyu', 'private', 'metro'],

    # Tokyu combos (4-operator)
    'map_station_rail_jr_tokyu_metro_toei.png': ['jr', 'tokyu', 'metro', 'toei'],
    'map_station_rail_jr_tobu_tokyu_metro.png': ['jr', 'tobu', 'tokyu', 'metro'],
    'map_station_rail_jr_seibu_tokyu_metro.png': ['jr', 'seibu', 'tokyu', 'metro'],
}

for fname, keys in combinations.items():
    out_path = os.path.join(DRAWABLE_DIR, fname)
    combined = create_combined(keys)
    combined.save(out_path)
    print(f'Generated {out_path} ({combined.width()}x{combined.height()}) for {keys}')

print('All combination station icons generated successfully.')
