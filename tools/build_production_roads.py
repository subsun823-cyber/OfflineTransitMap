import gzip
import math
import sqlite3
import os
import sys
import time
import heapq
from pmtiles.reader import MmapSource, all_tiles
import mapbox_vector_tile

sys.stdout.reconfigure(encoding='utf-8')

src_path = 'app/src/main/assets/bootstrap/tokyo.pmtiles'
out_path = 'app/src/main/assets/bootstrap/roads.db'

print(f"===========================================================")
print(f"Building Production roads.db (v9: tile-clipped & stitched) from {src_path}")
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

def point_line_distance(p, start, end):
    mid_lat = (start[0] + end[0]) * 0.5
    cos_lat = math.cos(math.radians(mid_lat))
    sx, sy = start[1] * cos_lat * 111320.0, start[0] * 110540.0
    ex, ey = end[1] * cos_lat * 111320.0, end[0] * 110540.0
    px, py = p[1] * cos_lat * 111320.0, p[0] * 110540.0
    
    dx = ex - sx
    dy = ey - sy
    mag = math.hypot(dx, dy)
    if mag < 1e-6:
        return math.hypot(px - sx, py - sy)
    return abs(dx * (sy - py) - dy * (sx - px)) / mag

def rdp(points, epsilon_m):
    if len(points) < 3:
        return points
    max_d = 0.0
    idx = 0
    start = points[0]
    end = points[-1]
    for i in range(1, len(points) - 1):
        d = point_line_distance(points[i], start, end)
        if d > max_d:
            max_d = d
            idx = i
    if max_d > epsilon_m:
        left = rdp(points[:idx+1], epsilon_m)
        right = rdp(points[idx:], epsilon_m)
        return left[:-1] + right
    else:
        return [start, end]

def clip_segment(p1, p2, box=(0.0, 0.0, 4096.0, 4096.0)):
    xmin, ymin, xmax, ymax = box
    x1, y1 = p1
    x2, y2 = p2
    dx = x2 - x1
    dy = y2 - y1
    p = [-dx, dx, -dy, dy]
    q = [x1 - xmin, xmax - x1, y1 - ymin, ymax - y1]
    u1, u2 = 0.0, 1.0
    for i in range(4):
        if p[i] == 0:
            if q[i] < 0:
                return None
        else:
            t = q[i] / p[i]
            if p[i] < 0:
                if t > u2:
                    return None
                if t > u1:
                    u1 = t
            else:
                if t < u1:
                    return None
                if t < u2:
                    u2 = t
    return ((x1 + u1 * dx, y1 + u1 * dy), (x1 + u2 * dx, y1 + u2 * dy))

def clip_polyline(pts, box=(0.0, 0.0, 4096.0, 4096.0)):
    result_lines = []
    current_line = []
    for i in range(len(pts) - 1):
        seg = clip_segment(pts[i], pts[i+1], box)
        if seg is not None:
            sp, ep = seg
            if not current_line:
                current_line.append(sp)
            else:
                if math.hypot(sp[0] - current_line[-1][0], sp[1] - current_line[-1][1]) > 0.01:
                    result_lines.append(current_line)
                    current_line = [sp]
            current_line.append(ep)
        else:
            if current_line:
                result_lines.append(current_line)
                current_line = []
    if current_line:
        result_lines.append(current_line)
    return result_lines

def classify_road(kind, kind_detail):
    if kind == 'major_road':
        if kind_detail in ['trunk', 'trunk_link', 'primary', 'primary_link']:
            return 1 # 大通り・主要幹線 (最優先・走りやすい)
        else:
            return 2 # 主要中規模道 (secondary, tertiary)
    elif kind == 'medium_road':
        return 2 # 主要中規模道
    elif kind == 'path':
        if kind_detail == 'cycleway':
            return 3 # 専用自転車道 (自転車最優先)
        elif kind_detail == 'steps':
            return 8 # 階段
        elif kind_detail in ['pedestrian', 'footway', 'path']:
            return 5 # 歩行者道・緑道・広場
        elif kind_detail == 'track':
            return 7 # 未舗装
        elif kind_detail == 'alley':
            return 6 # 路地
        else:
            return 5
    elif kind == 'minor_road':
        if kind_detail in ['service', 'alley']:
            return 6 # 細街路・裏道・サービス道 (通過を避ける)
        else:
            return 4 # 一般生活道路 (基準)
    return 4

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
        # CRUCIAL: y_coord_down=True prevents vector tile latitude inversion!
        tile = mapbox_vector_tile.decode(raw, default_options={'y_coord_down': True})
        extent = tile.get('roads', {}).get('extent', 4096)
        box = (0.0, 0.0, float(extent), float(extent))
        for r in tile.get('roads', {}).get('features', []):
            props = r.get('properties', {})
            k = props.get('kind', '')
            kd = props.get('kind_detail', '')
            
            # 高速道路除外
            if k == 'highway' or kd in ['motorway', 'motorway_link']:
                continue
            
            # 歩道・横断歩道・駐車場内通路・専用車庫は除外
            if kd in ['sidewalk', 'crossing', 'parking_aisle', 'driveway']:
                continue
            
            is_walk = 0
            is_bike = 0
            
            if k in ['major_road', 'medium_road']:
                is_walk = 1
                is_bike = 1
            elif k == 'minor_road':
                if kd in ['residential', 'unclassified', 'service', 'alley', '']:
                    is_walk = 1
                    is_bike = 1
            elif k == 'path':
                if kd == 'steps':
                    is_walk = 1
                    is_bike = 0 # 階段は自転車走行不可
                elif kd in ['cycleway', 'pedestrian', 'footway', 'path', 'track']:
                    is_walk = 1
                    is_bike = 1 # 広場・歩道・小道は押して通行可能
            
            if not is_walk and not is_bike:
                continue
            
            road_class = classify_road(k, kd)
            
            geom = r.get('geometry', {})
            coords = geom.get('coordinates', [])
            lines = [coords] if geom.get('type') == 'LineString' else (coords if geom.get('type') == 'MultiLineString' else [])
            for line in lines:
                if len(line) >= 2:
                    for cl in clip_polyline(line, box):
                        if len(cl) >= 2:
                            pts = [tile_to_latlon(14, zxy[1], zxy[2], pt[0], pt[1], extent) for pt in cl]
                            simplified_pts = rdp(pts, 3.0)
                            if len(simplified_pts) >= 2:
                                raw_lines.append((is_walk, is_bike, road_class, simplified_pts))

t1 = time.time()
print(f"Loaded {len(raw_lines)} road lines from {tile_count} tiles in {t1-t0:.2f}s")

# 空間スナップ（7m以内の近接ノードを統合）
SNAP_DIST = 7.0
SNAP_DEG = SNAP_DIST / 111000.0
node_coords = []
spatial_grid = {}

def get_snapped_node_id(lat, lon):
    gx = int(lon / SNAP_DEG)
    gy = int(lat / SNAP_DEG)
    for dy in (-1, 0, 1):
        for dx in (-1, 0, 1):
            for candidate_id in spatial_grid.get((gy + dy, gx + dx), []):
                clat, clon = node_coords[candidate_id]
                if distance_m(lat, lon, clat, clon) <= SNAP_DIST:
                    return candidate_id
    nid = len(node_coords)
    node_coords.append((lat, lon))
    spatial_grid.setdefault((gy, gx), []).append(nid)
    return nid

print("Building road network topology...")
adj = {}
for is_walk, is_bike, road_class, pts in raw_lines:
    for i in range(len(pts) - 1):
        u = get_snapped_node_id(pts[i][0], pts[i][1])
        v = get_snapped_node_id(pts[i+1][0], pts[i+1][1])
        if u != v:
            d = max(1, int(round(distance_m(pts[i][0], pts[i][1], pts[i+1][0], pts[i+1][1]))))
            if v in adj.get(u, {}):
                old_d, old_w, old_b, old_rc = adj[u][v]
                adj[u][v] = (min(old_d, d), max(old_w, is_walk), max(old_b, is_bike), min(old_rc, road_class))
                adj[v][u] = (min(old_d, d), max(old_w, is_walk), max(old_b, is_bike), min(old_rc, road_class))
            else:
                adj.setdefault(u, {})[v] = (d, is_walk, is_bike, road_class)
                adj.setdefault(v, {})[u] = (d, is_walk, is_bike, road_class)

print(f"Topology built: {len(node_coords)} nodes")

# 端点ステッチング（タイル境界や交差点でわずかに切断されたデッドエンドの自動縫合）
print("Performing stitching pass for disconnected endpoints...")
dead_ends = [u for u in range(len(node_coords)) if len(adj.get(u, {})) == 1]
print(f"Found {len(dead_ends)} dead-end nodes.")

stitch_count = 0
MAX_STITCH_DIST = 18.0  # 18m以内の切れ目を縫合
STITCH_CELL_RANGE = int(math.ceil(MAX_STITCH_DIST / SNAP_DIST))

for u in dead_ends:
    u_lat, u_lon = node_coords[u]
    gx = int(u_lon / SNAP_DEG)
    gy = int(u_lat / SNAP_DEG)
    best_v = None
    best_d = 999.0
    for dy in range(-STITCH_CELL_RANGE, STITCH_CELL_RANGE + 1):
        for dx in range(-STITCH_CELL_RANGE, STITCH_CELL_RANGE + 1):
            for v in spatial_grid.get((gy + dy, gx + dx), []):
                if v == u:
                    continue
                if v in adj.get(u, {}):
                    continue
                v_lat, v_lon = node_coords[v]
                d = distance_m(u_lat, u_lon, v_lat, v_lon)
                if d <= MAX_STITCH_DIST and d < best_d:
                    best_d = d
                    best_v = v
    if best_v is not None:
        d_m = max(1, int(round(best_d)))
        u_edge = list(adj[u].values())[0]
        v_edge = list(adj[v].values())[0] if adj.get(v) else u_edge
        
        stitch_walk = 1 if (u_edge[1] or v_edge[1]) else 0
        stitch_bike = 1 if (u_edge[2] or v_edge[2]) else 0
        stitch_rc = max(u_edge[3], v_edge[3])
        if stitch_rc == 8 and min(u_edge[3], v_edge[3]) != 8:
            stitch_rc = min(u_edge[3], v_edge[3])
        
        adj.setdefault(u, {})[best_v] = (d_m, stitch_walk, stitch_bike, stitch_rc)
        adj.setdefault(best_v, {})[u] = (d_m, stitch_walk, stitch_bike, stitch_rc)
        stitch_count += 1

print(f"Stitched {stitch_count} endpoint gaps (<= {MAX_STITCH_DIST}m).")

# 連結成分の抽出（20ノード以上の有効コンポーネントを保持）
visited = set()
components = []
for u in range(len(node_coords)):
    if u not in visited and u in adj:
        comp = []
        q = [u]
        visited.add(u)
        for node in q:
            comp.append(node)
            for neighbor in adj.get(node, {}):
                if neighbor not in visited:
                    visited.add(neighbor)
                    q.append(neighbor)
        components.append(comp)

components.sort(key=lambda c: len(c), reverse=True)
print(f"Total connected components: {len(components)}")
for i in range(min(5, len(components))):
    print(f"  Comp #{i+1}: {len(components[i])} nodes")

valid_nodes = set()
for c in components:
    if len(c) >= 20:
        valid_nodes.update(c)

print(f"Valid active nodes: {len(valid_nodes)} / {len(node_coords)}")

# 美山町（馬込松木〜御屋敷入口）のナビ検証
magome = (35.688568, 139.251249)
oyashiki = (35.68627, 139.25291)

def find_nearest_node(lat, lon):
    best_id = -1
    best_d = 999999.0
    for nid in valid_nodes:
        nlat, nlon = node_coords[nid]
        if abs(lat - nlat) < 0.005 and abs(lon - nlon) < 0.005:
            d = distance_m(lat, lon, nlat, nlon)
            if d < best_d:
                best_d = d
                best_id = nid
    return best_id, best_d

sn_id, sn_d = find_nearest_node(magome[0], magome[1])
gn_id, gn_d = find_nearest_node(oyashiki[0], oyashiki[1])
print(f"Miyama verification: Magome snap: {sn_id} ({sn_d:.1f}m), Oyashiki snap: {gn_id} ({gn_d:.1f}m)")

dist_map = {sn_id: 0.0}
prev_map = {}
init_h = distance_m(node_coords[sn_id][0], node_coords[sn_id][1], node_coords[gn_id][0], node_coords[gn_id][1]) * 0.70
pq = [(init_h, 0.0, sn_id)]

while pq:
    prio, d, u = heapq.heappop(pq)
    if u == gn_id:
        break
    if d > dist_map.get(u, 1e9):
        continue
    for v, (weight, is_walk, is_bike, road_class) in adj.get(u, {}).items():
        if v not in valid_nodes:
            continue
        # 大通り優遇コスト
        class_factor = 0.85 if road_class in [1, 2] else (1.35 if road_class == 6 else 1.0)
        new_d = d + weight * class_factor
        if new_d < dist_map.get(v, 1e9):
            dist_map[v] = new_d
            prev_map[v] = u
            h = distance_m(node_coords[v][0], node_coords[v][1], node_coords[gn_id][0], node_coords[gn_id][1]) * 0.70
            heapq.heappush(pq, (new_d + h, new_d, v))

if gn_id in dist_map:
    path = []
    curr = gn_id
    real_dist = 0
    while curr != sn_id:
        p = prev_map[curr]
        real_dist += adj[p][curr][0]
        path.append(curr)
        curr = p
    path.append(sn_id)
    path.reverse()
    print(f"VERIFIED! Miyama route found: real ground distance={real_dist}m across {len(path)} waypoints")
else:
    print("WARNING: Miyama route NOT found!")

# 八王子明神町（甲州街道）〜大和田町五丁目（エコタウン）のナビ検証
myojin = (35.6580, 139.3425)
ecotown = (35.663041, 139.352576)

s_id, s_d = find_nearest_node(myojin[0], myojin[1])
g_id, g_d = find_nearest_node(ecotown[0], ecotown[1])
print(f"Hachioji verification: Myojin snap: {s_id} ({s_d:.1f}m), Ecotown snap: {g_id} ({g_d:.1f}m)")

dist_map_h = {s_id: 0.0}
prev_map_h = {}
init_h = distance_m(node_coords[s_id][0], node_coords[s_id][1], node_coords[g_id][0], node_coords[g_id][1]) * 0.70
pq_h = [(init_h, 0.0, s_id)]

while pq_h:
    prio, d, u = heapq.heappop(pq_h)
    if u == g_id:
        break
    if d > dist_map_h.get(u, 1e9):
        continue
    for v, (weight, is_walk, is_bike, road_class) in adj.get(u, {}).items():
        if v not in valid_nodes or not is_bike:
            continue
        class_factor = 0.82 if road_class == 1 else (0.88 if road_class == 2 else (0.85 if road_class == 3 else (1.35 if road_class == 6 else 1.0)))
        new_d = d + weight * class_factor
        if new_d < dist_map_h.get(v, 1e9):
            dist_map_h[v] = new_d
            prev_map_h[v] = u
            h = distance_m(node_coords[v][0], node_coords[v][1], node_coords[g_id][0], node_coords[g_id][1]) * 0.70
            heapq.heappush(pq_h, (new_d + h, new_d, v))

if g_id in dist_map_h:
    path_h = []
    curr = g_id
    real_dist_h = 0
    while curr != s_id:
        p = prev_map_h[curr]
        real_dist_h += adj[p][curr][0]
        path_h.append(curr)
        curr = p
    path_h.append(s_id)
    path_h.reverse()
    print(f"VERIFIED! Hachioji Owada route found: real ground distance={real_dist_h}m across {len(path_h)} waypoints (Direct Koshu-kaido bridge route)")
else:
    print("WARNING: Hachioji Owada route NOT found!")

# SQLite 保存
temp_out = out_path + ".tmp"
if os.path.exists(temp_out):
    os.remove(temp_out)

conn = sqlite3.connect(temp_out)
cur = conn.cursor()
cur.execute("PRAGMA journal_mode = OFF")
cur.execute("PRAGMA synchronous = OFF")
cur.execute("PRAGMA page_size = 4096")

cur.execute("""
CREATE TABLE road_nodes (
    id INTEGER PRIMARY KEY,
    lat REAL NOT NULL,
    lon REAL NOT NULL,
    grid INTEGER NOT NULL
)
""")

cur.execute("""
CREATE TABLE road_edges (
    id INTEGER PRIMARY KEY,
    u INTEGER NOT NULL,
    v INTEGER NOT NULL,
    dist_m INTEGER NOT NULL,
    is_walk INTEGER NOT NULL,
    is_bike INTEGER NOT NULL,
    road_class INTEGER NOT NULL,
    grid INTEGER NOT NULL
)
""")

new_id_map = {}
new_nodes = []
for old_id in sorted(valid_nodes):
    nid = len(new_nodes)
    new_id_map[old_id] = nid
    new_nodes.append(node_coords[old_id])

print(f"Writing {len(new_nodes)} nodes to {temp_out}...")
node_rows = [
    (nid, pt[0], pt[1], grid_cell_key(pt[0], pt[1]))
    for nid, pt in enumerate(new_nodes)
]
cur.executemany("INSERT INTO road_nodes (id, lat, lon, grid) VALUES (?, ?, ?, ?)", node_rows)

print("Writing edges to SQLite...")
edge_rows = []
eid = 0
visited_edges = set()
for old_u in valid_nodes:
    nu = new_id_map[old_u]
    pt = new_nodes[nu]
    grid = grid_cell_key(pt[0], pt[1])
    for old_v, (dist_m, is_walk, is_bike, road_class) in adj.get(old_u, {}).items():
        if old_v in valid_nodes:
            pair = (min(old_u, old_v), max(old_u, old_v))
            if pair not in visited_edges:
                visited_edges.add(pair)
                nv = new_id_map[old_v]
                edge_rows.append((eid, nu, nv, dist_m, is_walk, is_bike, road_class, grid))
                eid += 1

cur.executemany("INSERT INTO road_edges (id, u, v, dist_m, is_walk, is_bike, road_class, grid) VALUES (?, ?, ?, ?, ?, ?, ?, ?)", edge_rows)

print("Creating spatial grid indices...")
cur.execute("CREATE INDEX idx_road_nodes_grid ON road_nodes(grid)")
cur.execute("CREATE INDEX idx_road_edges_grid ON road_edges(grid)")

conn.commit()
cur.execute("VACUUM")
conn.close()

if os.path.exists(out_path):
    os.remove(out_path)
os.rename(temp_out, out_path)

final_size = os.path.getsize(out_path)
print(f"SUCCESS! {out_path} created with road_class: {final_size / (1024*1024):.2f} MB ({final_size} bytes)")
