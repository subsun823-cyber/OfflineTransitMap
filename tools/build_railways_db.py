#!/usr/bin/env python3
"""
tokyo.pmtiles から鉄道線路（roads レイヤーの kind == 'rail'）を抽出し、
鉄道ナビゲーション・線路追従用の軽量な SQLite データベース (railways.db) を構築するスクリプト。
"""

import gzip
import math
import sqlite3
import os
import sys
import time
from pmtiles.reader import MmapSource, all_tiles
import mapbox_vector_tile

sys.stdout.reconfigure(encoding='utf-8')

src_path = sys.argv[1] if len(sys.argv) > 1 else 'app/src/main/assets/bootstrap/tokyo.pmtiles'
out_path = sys.argv[2] if len(sys.argv) > 2 else 'app/src/main/assets/bootstrap/railways.db'

print(f"===========================================================")
print(f"Building railways.db from {src_path}")
print(f"Target: {out_path}")
print(f"===========================================================")

src = MmapSource(open(src_path, 'rb'))

def tile_to_latlon(z, x, y, px, py, extent=4096):
    n = 2.0 ** z
    lon_deg = (x + px / float(extent)) / n * 360.0 - 180.0
    my = y + py / float(extent)
    lat_rad = math.atan(math.sinh(math.pi * (1.0 - 2.0 * my / n)))
    lat_deg = math.degrees(lat_rad)
    return lat_deg, lon_deg

def distance_m(lat1, lon1, lat2, lon2):
    dy = (lat2 - lat1) * 110540.0
    dx = (lon2 - lon1) * math.cos(math.radians((lat1 + lat2) * 0.5)) * 111320.0
    return math.hypot(dx, dy)

GRID_CELL_DEG = 0.01
def grid_cell_key(lat, lon):
    y = int(lat / GRID_CELL_DEG)
    x = int(lon / GRID_CELL_DEG)
    return ((y & 0xFFFF) << 16) | (x & 0xFFFF)

t0 = time.time()
raw_lines = []
tile_count = 0

for zxy, data in all_tiles(src):
    if zxy[0] == 14:
        tile_count += 1
        raw = gzip.decompress(data) if data[:2] == b'\x1f\x8b' else data
        tile = mapbox_vector_tile.decode(raw, default_options={'y_coord_down': True})
        roads = tile.get('roads', {}).get('features', [])
        extent = tile.get('roads', {}).get('extent', 4096)
        for f in roads:
            props = f.get('properties', {})
            k = props.get('kind', '')
            kd = props.get('kind_detail', '')
            if k == 'rail' or kd == 'rail':
                geom = f.get('geometry', {})
                coords = geom.get('coordinates', [])
                g_type = geom.get('type', '')
                line_list = []
                if g_type == 'LineString':
                    line_list = [coords]
                elif g_type == 'MultiLineString':
                    line_list = coords
                
                name = props.get('name:ja') or props.get('name') or props.get('name:en') or ''
                for l in line_list:
                    if len(l) >= 2:
                        pts = [tile_to_latlon(zxy[0], zxy[1], zxy[2], p[0], p[1], extent) for p in l]
                        raw_lines.append((name, pts))

print(f"Scanned {tile_count} tiles in {time.time()-t0:.2f}s, extracted {len(raw_lines)} rail polylines")

# ノードの統合（同一座標点または微小距離点 2m 以内をマージ）
node_map = {} # (round_lat, round_lon) -> node_id
nodes = []    # [(node_id, lat, lon)]
edges = []    # [(u, v, dist_m, name)]
seen_edges = set()

def get_or_create_node(lat, lon):
    # 5 decimal places ~= 1.1m resolution
    key = (round(lat, 5), round(lon, 5))
    if key in node_map:
        return node_map[key]
    nid = len(nodes) + 1
    node_map[key] = nid
    nodes.append((nid, lat, lon))
    return nid

for name, pts in raw_lines:
    prev_id = None
    for lat, lon in pts:
        curr_id = get_or_create_node(lat, lon)
        if prev_id is not None and prev_id != curr_id:
            u, v = min(prev_id, curr_id), max(prev_id, curr_id)
            if (u, v) not in seen_edges:
                seen_edges.add((u, v))
                u_lat, u_lon = nodes[u-1][1], nodes[u-1][2]
                v_lat, v_lon = nodes[v-1][1], nodes[v-1][2]
                d = max(1, int(round(distance_m(u_lat, u_lon, v_lat, v_lon))))
                edges.append((u, v, d, name))
        prev_id = curr_id

print(f"Initial graph: {len(nodes)} nodes, {len(edges)} edges")

# タイル境界やギャップのステッチング（未接続端点同士で 25m 以内のものを接続）
adj_count = {}
for u, v, d, _ in edges:
    adj_count[u] = adj_count.get(u, 0) + 1
    adj_count[v] = adj_count.get(v, 0) + 1

dead_ends = [nid for nid, count in adj_count.items() if count == 1]
print(f"Dead-end nodes to stitch: {len(dead_ends)}")

# 空間グリッドでステッチング候補を探索
fine_grid = {}
fine_deg = 0.001 # 約100m
for nid, lat, lon in nodes:
    if adj_count.get(nid, 0) <= 2:
        fy = int(lat / fine_deg)
        fx = int(lon / fine_deg)
        fine_grid.setdefault((fy, fx), []).append((nid, lat, lon))

stitched = 0
for u in dead_ends:
    u_lat, u_lon = nodes[u-1][1], nodes[u-1][2]
    fy = int(u_lat / fine_deg)
    fx = int(u_lon / fine_deg)
    best_v = None
    best_d = 120.0 # 最大120m (駅構内や橋梁のギャップを確実に縫合)
    for dy in (-1, 0, 1):
        for dx in (-1, 0, 1):
            for v, v_lat, v_lon in fine_grid.get((fy + dy, fx + dx), []):
                if u != v:
                    pair = (min(u, v), max(u, v))
                    if pair not in seen_edges:
                        d = distance_m(u_lat, u_lon, v_lat, v_lon)
                        if d < best_d:
                            best_d = d
                            best_v = v
    if best_v is not None:
        pair = (min(u, best_v), max(u, best_v))
        seen_edges.add(pair)
        d_int = max(1, int(round(best_d)))
        edges.append((pair[0], pair[1], d_int, 'rail_link'))
        adj_count[u] = adj_count.get(u, 0) + 1
        adj_count[best_v] = adj_count.get(best_v, 0) + 1
        stitched += 1

print(f"Stitched {stitched} boundary gaps")
print(f"Final graph: {len(nodes)} nodes, {len(edges)} edges")

# SQLite に保存
if os.path.exists(out_path):
    os.remove(out_path)

conn = sqlite3.connect(out_path)
cur = conn.cursor()

cur.execute("PRAGMA journal_mode = OFF")
cur.execute("PRAGMA synchronous = OFF")

cur.execute("""
    CREATE TABLE rail_nodes (
        id INTEGER PRIMARY KEY,
        lat REAL NOT NULL,
        lon REAL NOT NULL,
        grid INTEGER NOT NULL
    )
""")

cur.execute("""
    CREATE TABLE rail_edges (
        u INTEGER NOT NULL,
        v INTEGER NOT NULL,
        dist_m INTEGER NOT NULL,
        name TEXT,
        grid INTEGER NOT NULL
    )
""")

node_rows = []
for nid, lat, lon in nodes:
    grid = grid_cell_key(lat, lon)
    node_rows.append((nid, lat, lon, grid))

cur.executemany("INSERT INTO rail_nodes (id, lat, lon, grid) VALUES (?, ?, ?, ?)", node_rows)

edge_rows = []
for u, v, dist_m, name in edges:
    u_lat, u_lon = nodes[u-1][1], nodes[u-1][2]
    grid = grid_cell_key(u_lat, u_lon)
    edge_rows.append((u, v, dist_m, name, grid))

cur.executemany("INSERT INTO rail_edges (u, v, dist_m, name, grid) VALUES (?, ?, ?, ?, ?)", edge_rows)

print("Building indices...")
cur.execute("CREATE INDEX idx_rail_nodes_grid ON rail_nodes(grid)")
cur.execute("CREATE INDEX idx_rail_edges_grid ON rail_edges(grid)")
cur.execute("CREATE INDEX idx_rail_edges_u ON rail_edges(u)")
cur.execute("CREATE INDEX idx_rail_edges_v ON rail_edges(v)")

conn.commit()
conn.close()

size_mb = os.path.getsize(out_path) / (1024 * 1024)
print(f"Successfully generated {out_path} ({size_mb:.2f} MB) in {time.time()-t0:.2f}s")
