import sys
import gzip
import sqlite3
import math
import os
from pmtiles.reader import MmapSource, all_tiles
import mapbox_vector_tile

sys.stdout.reconfigure(encoding='utf-8')

def tile_to_latlon(z, x, y, px, py, extent=4096):
    n = 2.0 ** z
    lon_deg = (x + px / float(extent)) / n * 360.0 - 180.0
    my = y + py / float(extent)
    lat_rad = math.atan(math.sinh(math.pi * (1.0 - 2.0 * my / n)))
    lat_deg = math.degrees(lat_rad)
    return lat_deg, lon_deg

def map_category(kind):
    if kind in ['convenience', 'supermarket', 'grocery', 'bakery', 'mall', 'department_store', 'clothes', 'books', 'florist', 'gift', 'shop']:
        return 'COMMERCIAL'
    if kind in ['restaurant', 'cafe', 'fast_food', 'bar', 'pub', 'food_court']:
        return 'COMMERCIAL'
    if kind in ['hospital', 'clinic', 'doctors', 'dentist', 'pharmacy']:
        return 'FACILITY'
    if kind in ['school', 'college', 'university', 'kindergarten']:
        return 'FACILITY'
    if kind in ['park', 'garden', 'playground', 'nature_reserve', 'forest', 'dog_park', 'picnic_site']:
        return 'PARK'
    if kind in ['place_of_worship', 'shrine', 'temple', 'church', 'mosque']:
        return 'LANDMARK'
    if kind in ['hotel', 'motel', 'hostel', 'guest_house', 'bed_and_breakfast']:
        return 'COMMERCIAL'
    if kind in ['townhall', 'community_centre', 'post_office', 'police', 'fire_station', 'bank', 'library', 'museum']:
        return 'FACILITY'
    if kind in ['theme_park', 'zoo', 'aquarium', 'attraction', 'viewpoint']:
        return 'LEISURE'
    return 'FACILITY'

def kind_label(kind):
    mapping = {
        'convenience': 'コンビニ',
        'supermarket': 'スーパー',
        'restaurant': '飲食店',
        'cafe': 'カフェ',
        'fast_food': 'ファストフード',
        'bakery': 'パン屋',
        'pharmacy': '薬局・ドラッグストア',
        'hospital': '病院',
        'clinic': 'クリニック・診療所',
        'dentist': '歯科医院',
        'school': '学校',
        'university': '大学',
        'college': '専門学校・短大',
        'kindergarten': '保育園・幼稚園',
        'park': '公園',
        'shrine': '神社',
        'temple': '寺院',
        'church': '教会',
        'post_office': '郵便局',
        'police': '警察署・交番',
        'fire_station': '消防署',
        'bank': '銀行',
        'library': '図書館',
        'museum': '博物館・美術館',
        'hotel': 'ホテル・宿泊',
        'parking': '駐車場',
        'gas_station': 'ガソリンスタンド'
    }
    return mapping.get(kind, '施設')

output_db = 'app/src/main/assets/bootstrap/pois.db'
if os.path.exists(output_db):
    os.remove(output_db)

conn = sqlite3.connect(output_db)
cur = conn.cursor()
cur.execute("""
CREATE TABLE pois (
    id TEXT PRIMARY KEY,
    name TEXT NOT NULL,
    lat REAL NOT NULL,
    lon REAL NOT NULL,
    kind TEXT NOT NULL,
    category TEXT NOT NULL,
    detail TEXT NOT NULL
)
""")
cur.execute("CREATE INDEX idx_pois_name ON pois(name)")
cur.execute("CREATE INDEX idx_pois_kind ON pois(kind)")
cur.execute("CREATE INDEX idx_pois_category ON pois(category)")

src = MmapSource(open('app/src/main/assets/bootstrap/tokyo.pmtiles', 'rb'))

seen = set()
batch = []
total_inserted = 0

print("Extracting POIs from tokyo.pmtiles...")

for zxy, data in all_tiles(src):
    z, x, y = zxy
    if z != 15:
        continue
    try:
        raw = gzip.decompress(data) if data[:2] == b'\x1f\x8b' else data
        tile = mapbox_vector_tile.decode(raw, default_options={'y_coord_down': True})
    except Exception:
        continue
        
    pois = tile.get('pois', {}).get('features', [])
    for f in pois:
        props = f.get('properties', {})
        name = props.get('name')
        if not name or len(name.strip()) == 0:
            continue
        kind = props.get('kind', 'poi')
        if kind in ['bus_stop', 'bus_station']:
            continue
            
        geom = f.get('geometry', {})
        coords = geom.get('coordinates', [])
        if not coords or not isinstance(coords[0], (int, float)):
            continue
            
        px, py = coords[0], coords[1]
        lat, lon = tile_to_latlon(z, x, y, px, py)
        
        # 重複除外キー（同一名・近接座標を重複排除）
        # 約10m以内の同名スポットを重複排除
        grid_key = f"{name}_{round(lat, 4)}_{round(lon, 4)}"
        if grid_key in seen:
            continue
        seen.add(grid_key)
        
        poi_id = f"poi_{len(seen)}"
        category = map_category(kind)
        detail = kind_label(kind)
        
        batch.append((poi_id, name.strip(), lat, lon, kind, category, detail))
        if len(batch) >= 5000:
            cur.executemany("INSERT INTO pois VALUES (?, ?, ?, ?, ?, ?, ?)", batch)
            total_inserted += len(batch)
            batch = []

if batch:
    cur.executemany("INSERT INTO pois VALUES (?, ?, ?, ?, ?, ?, ?)", batch)
    total_inserted += len(batch)

conn.commit()
cur.execute("VACUUM")
conn.close()

db_size = os.path.getsize(output_db) / (1024 * 1024)
print(f"Successfully created {output_db}!")
print(f"Total POIs inserted: {total_inserted}")
print(f"Database size: {db_size:.2f} MB")
