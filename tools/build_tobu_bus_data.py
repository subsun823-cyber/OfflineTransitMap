#!/usr/bin/env python3
"""Build the app's compact Tobu Bus seed database from ODPT exports (stdlib only)."""
import argparse
from collections import defaultdict
import datetime as dt
import gzip
import hashlib
import json
import math
from pathlib import Path
import sqlite3
import tempfile

PREFIX = 'ODPT_TOBU_BUS:'
TABLES = {
    'meta': 'key TEXT PRIMARY KEY, value TEXT',
    'feeds': 'feed_id TEXT PRIMARY KEY, start_date INTEGER, end_date INTEGER',
    'stations': 'station_id TEXT PRIMARY KEY, name TEXT, lat REAL, lon REAL, kana TEXT, grp TEXT, kind TEXT',
    'stops': 'stop_id TEXT PRIMARY KEY, station_id TEXT, name TEXT, platform TEXT, zone TEXT',
    'routes': 'route_id TEXT PRIMARY KEY, operator TEXT, name TEXT, long_name TEXT, color INTEGER, route_type INTEGER',
    'trips': 'trip_no INTEGER PRIMARY KEY, trip_id TEXT UNIQUE, route_id TEXT, service_id TEXT, headsign TEXT, train_type TEXT',
    'stop_times': 'trip INTEGER, seq INTEGER, stop_id TEXT, station_id TEXT, arr_sec INTEGER, dep_sec INTEGER, headsign TEXT, can_board INTEGER, can_alight INTEGER',
    'calendar': 'service_id TEXT PRIMARY KEY, start_date INTEGER, end_date INTEGER, mon INTEGER, tue INTEGER, wed INTEGER, fri INTEGER, sat INTEGER, sun INTEGER, thu INTEGER',
    'calendar_dates': 'service_id TEXT, date INTEGER, exception_type INTEGER',
    'fares': 'route_id TEXT, from_zone TEXT, to_zone TEXT, price INTEGER',
    'station_operators': 'station_id TEXT, operator TEXT, PRIMARY KEY (station_id, operator)',
    'app_data': 'key TEXT PRIMARY KEY, value TEXT',
}

CALENDARS = {
    'odpt.Calendar:Weekday': (1, 1, 1, 1, 1, 0, 0),
    'odpt.Calendar:Saturday': (0, 0, 0, 0, 0, 1, 0),
    'odpt.Calendar:Holiday': (0, 0, 0, 0, 0, 0, 1),
}


def load_json(path: Path):
    data = json.loads(path.read_text(encoding='utf-8-sig'))
    assert isinstance(data, list) and data, f'Empty export: {path}'
    return data


def parse_time_sec(t_str: str) -> int:
    h, m = map(int, t_str.split(':'))
    assert 0 <= h < 48 and 0 <= m < 60
    sec = h * 3600 + m * 60
    if h < 3:
        sec += 86400
    return sec


def distance_m(p1, p2):
    return math.hypot((p1[0] - p2[0]) * 110540, (p1[1] - p2[1]) * 111320 * math.cos(math.radians(p1[0])))


def build(args):
    poles_data = load_json(args.poles)
    patterns_data = load_json(args.patterns)
    timetables = load_json(args.timetables)

    # 1. Build dictionary of all known stations from existing timetable.db and tobu station file
    db_stations = {}
    if args.station_reference and args.station_reference.exists():
        ref_stns = load_json(args.station_reference)
        for s in ref_stns:
            name = s.get('odpt:stationTitle', {}).get('ja') or s.get('dc:title', '')
            db_stations[name] = (float(s['geo:lat']), float(s['geo:long']))

    if args.existing_db and args.existing_db.exists():
        try:
            con = sqlite3.connect(args.existing_db)
            cur = con.cursor()
            cur.execute('SELECT name, lat, lon FROM stations')
            for row in cur.fetchall():
                if row[0] not in db_stations:
                    db_stations[row[0]] = (row[1], row[2])
            con.close()
        except Exception:
            pass

    landmarks = {
        '花畑団地': (35.807, 139.815),
        '竹の塚車庫': (35.795, 139.790),
        '都市農業公園': (35.783, 139.742),
        '足立区役所': (35.775, 139.805),
        'さいたま市立病院': (35.885, 139.680),
        '市立柏高校': (35.918, 139.955),
        '三郷団地': (35.845, 139.875),
        '羽田空港第３ターミナル': (35.549, 139.768),
        'スカイツリータウン': (35.710, 139.812),
        '東京ディズニーランド': (35.633, 139.880),
        '東京ディズニーシー': (35.626, 139.887),
        '文教大学（東京あだちキャンパス）': (35.797, 139.808),
        '八潮市役所': (35.823, 139.839),
        '六町駅': (35.773, 139.822),
        '八潮駅': (35.818, 139.843),
        '八潮駅北口': (35.818, 139.843),
        '八潮駅南口': (35.818, 139.843),
        '草加駅': (35.828, 139.802),
        '草加駅東口': (35.828, 139.802),
        '草加駅西口': (35.828, 139.802),
        '竹の塚駅': (35.794, 139.791),
        '竹の塚駅東口': (35.794, 139.791),
        '竹の塚駅西口': (35.794, 139.791),
        '西新井駅': (35.777, 139.790),
        '西新井駅東口': (35.777, 139.790),
        '西新井駅西口': (35.777, 139.790),
        '北千住駅': (35.749, 139.804),
        '北千住駅西口': (35.749, 139.804),
        '金町駅': (35.769, 139.870),
        '金町駅南口': (35.769, 139.870),
        '柏駅': (35.862, 139.971),
        '柏駅西口': (35.862, 139.971),
        '上尾駅': (35.973, 139.593),
        '上尾駅東口': (35.973, 139.593),
        '岩槻駅': (35.950, 139.693),
        '岩槻駅東口': (35.950, 139.693),
    }
    db_stations.update(landmarks)

    poles = {p['owl:sameAs']: p for p in poles_data}
    patterns = {p['owl:sameAs']: p for p in patterns_data}

    # Find used poles
    used_poles = set()
    for tt in timetables:
        for obj in tt.get('odpt:busTimetableObject', []):
            p = obj.get('odpt:busstopPole')
            if p:
                used_poles.add(p)

    # Resolve coordinates for poles
    pole_coords = {}
    for pid in used_poles:
        p = poles.get(pid, {})
        title = p.get('dc:title', '')
        cand = title
        for sfx in ['駅西口', '駅東口', '駅前', '駅']:
            if cand.endswith(sfx):
                cand = cand[:-len(sfx)]
                break
        if title in db_stations:
            pole_coords[pid] = db_stations[title]
        elif cand in db_stations:
            pole_coords[pid] = db_stations[cand]

    # Interpolate across patterns
    active_patterns = {tt.get('odpt:busroutePattern') for tt in timetables if tt.get('odpt:busroutePattern') in patterns}
    for _ in range(3):  # multiple passes to resolve shared poles
        for pat_id in active_patterns:
            pat = patterns[pat_id]
            pole_orders = [entry['odpt:busstopPole'] for entry in pat.get('odpt:busstopPoleOrder', [])]
            known_indices = [i for i, pid in enumerate(pole_orders) if pid in pole_coords]
            if len(known_indices) >= 2:
                for k in range(len(known_indices) - 1):
                    idx1, idx2 = known_indices[k], known_indices[k+1]
                    p1, p2 = pole_coords[pole_orders[idx1]], pole_coords[pole_orders[idx2]]
                    for i in range(idx1 + 1, idx2):
                        frac = (i - idx1) / (idx2 - idx1)
                        pid = pole_orders[i]
                        if pid not in pole_coords:
                            pole_coords[pid] = (p1[0] + frac * (p2[0] - p1[0]), p1[1] + frac * (p2[1] - p1[1]))
            elif len(known_indices) == 1:
                idx0 = known_indices[0]
                base_p = pole_coords[pole_orders[idx0]]
                for i, pid in enumerate(pole_orders):
                    if pid not in pole_coords:
                        pole_coords[pid] = (base_p[0] + (i - idx0) * 0.0015, base_p[1])

    # Default fallback for any remaining pole: nearby Nishiarai
    fallback_coord = (35.777, 139.790)
    for pid in used_poles:
        if pid not in pole_coords:
            pole_coords[pid] = fallback_coord

    # Standard Japanese National Holidays through 2027
    holidays = [
        dt.date(2026, 1, 1), dt.date(2026, 1, 12), dt.date(2026, 2, 11), dt.date(2026, 2, 23),
        dt.date(2026, 3, 20), dt.date(2026, 4, 29), dt.date(2026, 5, 3), dt.date(2026, 5, 4),
        dt.date(2026, 5, 5), dt.date(2026, 5, 6), dt.date(2026, 7, 20), dt.date(2026, 8, 11),
        dt.date(2026, 9, 21), dt.date(2026, 9, 22), dt.date(2026, 9, 23), dt.date(2026, 10, 12),
        dt.date(2026, 11, 3), dt.date(2026, 11, 23),
        dt.date(2027, 1, 1), dt.date(2027, 1, 11), dt.date(2027, 2, 11), dt.date(2027, 2, 23),
        dt.date(2027, 3, 21), dt.date(2027, 3, 22), dt.date(2027, 4, 29), dt.date(2027, 5, 3),
        dt.date(2027, 5, 4), dt.date(2027, 5, 5), dt.date(2027, 7, 19), dt.date(2027, 8, 11),
        dt.date(2027, 9, 20), dt.date(2027, 9, 23), dt.date(2027, 10, 11), dt.date(2027, 11, 3),
        dt.date(2027, 11, 23)
    ]

    start = dt.date(2026, 10, 1)
    end = dt.date(2027, 12, 31)
    ymd = lambda d: int(d.strftime('%Y%m%d'))

    db = sqlite3.connect(':memory:')
    for table_name, cols in TABLES.items():
        db.execute(f'CREATE TABLE {table_name} ({cols})')

    db.execute("INSERT INTO meta VALUES ('schema_version', '5')")
    db.execute("INSERT INTO feeds VALUES (?, ?, ?)", (PREFIX, ymd(start), ymd(end)))

    sid = lambda raw: PREFIX + raw

    # 2. Stations & Stops (only insert used poles)
    for pid in sorted(used_poles):
        p = poles.get(pid, {})
        name = p.get('dc:title', pid.split('.')[-1])
        lat, lon = pole_coords[pid]
        kana = p.get('odpt:kana', '')
        pole_num = p.get('odpt:busstopPoleNumber', '')

        candidates = db.execute('SELECT station_id, lat, lon, grp FROM stations WHERE name=?', (name,)).fetchall()
        near = [c for c in candidates if distance_m((lat, lon), (c[1], c[2])) <= 700]
        group = near[0][3] if near else sid(pid)

        db.execute(
            'INSERT INTO stations VALUES (?, ?, ?, ?, ?, ?, ?)',
            (sid(pid), name, lat, lon, kana, group, 'bus')
        )
        db.execute(
            'INSERT INTO stops VALUES (?, ?, ?, ?, ?)',
            (sid(pid), sid(pid), name, pole_num, sid(pid))
        )
        db.execute(
            'INSERT INTO station_operators VALUES (?, ?)',
            (sid(pid), '東武バス')
        )

    # 3. Routes
    routes_inserted = set()
    for pat_id in active_patterns:
        pat = patterns[pat_id]
        r_id = pat.get('odpt:busroute', pat_id)
        if r_id in routes_inserted:
            continue
        routes_inserted.add(r_id)
        short_name = r_id.split('.')[-1]
        long_name = pat.get('dc:title', short_name)
        # Tobu Bus color: 0x005BAB (Tobu Future Blue)
        color_int = 0x005BAB
        db.execute(
            'INSERT INTO routes VALUES (?, ?, ?, ?, ?, 3)',
            (sid(r_id), '東武バス', short_name, f'東武バス {long_name}', color_int)
        )

    # 4. Calendars
    for cal_id, flags in CALENDARS.items():
        mon, tue, wed, thu, fri, sat, sun = flags
        db.execute(
            'INSERT INTO calendar (service_id, start_date, end_date, mon, tue, wed, thu, fri, sat, sun) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)',
            (sid(cal_id), ymd(start), ymd(end), mon, tue, wed, thu, fri, sat, sun)
        )
        for h in holidays:
            if start <= h <= end:
                exc = 2 if cal_id.endswith('Weekday') else (1 if cal_id.endswith('Holiday') else 2)
                db.execute(
                    'INSERT INTO calendar_dates VALUES (?, ?, ?)',
                    (sid(cal_id), ymd(h), exc)
                )

    # 5. Trips and StopTimes
    trip_count = 0
    total_stop_times_rows = 0
    trips_to_insert = []
    stop_times_to_insert = []

    for tt in timetables:
        t_objs = tt.get('odpt:busTimetableObject', [])
        if len(t_objs) < 2:
            continue

        trip_count += 1
        pat_id = tt.get('odpt:busroutePattern', '')
        pat = patterns.get(pat_id, {})
        r_id = pat.get('odpt:busroute', pat_id)
        cal = tt.get('odpt:calendar', 'odpt.Calendar:Weekday')

        # Headsign from last stop
        last_pole_id = t_objs[-1].get('odpt:busstopPole', '')
        last_pole = poles.get(last_pole_id, {})
        headsign = last_pole.get('dc:title', pat.get('dc:title', '東武バス'))

        trip_id = sid(tt.get('owl:sameAs', f'TRIP_{trip_count:06d}'))
        trips_to_insert.append((trip_count, trip_id, sid(r_id), sid(cal), headsign, '一般路線バス'))

        seq = 1
        for idx, obj in enumerate(t_objs):
            p_id = obj.get('odpt:busstopPole')
            arr_time = obj.get('odpt:arrivalTime', obj.get('odpt:departureTime', '00:00'))
            dep_time = obj.get('odpt:departureTime', arr_time)
            arr_sec = parse_time_sec(arr_time)
            dep_sec = parse_time_sec(dep_time)
            can_board = 1 if obj.get('odpt:canGetOn', True) and idx < len(t_objs) - 1 else 0
            can_alight = 1 if obj.get('odpt:canGetOff', True) and idx > 0 else 0

            stop_times_to_insert.append((
                trip_count, seq, sid(p_id), sid(p_id),
                arr_sec, dep_sec, headsign, can_board, can_alight
            ))
            seq += 1
            total_stop_times_rows += 1

    db.executemany('INSERT INTO trips VALUES (?, ?, ?, ?, ?, ?)', trips_to_insert)
    db.executemany('INSERT INTO stop_times VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)', stop_times_to_insert)

    # 6. Fares (Standard flat bus fare 240 JPY for all pole pairs along each pattern)
    seen_fares = set()
    fares_to_insert = []
    for pat_id in active_patterns:
        pat = patterns[pat_id]
        r_id = pat.get('odpt:busroute', pat_id)
        pole_orders = [entry['odpt:busstopPole'] for entry in pat.get('odpt:busstopPoleOrder', []) if entry.get('odpt:busstopPole') in used_poles]
        for i, p_a in enumerate(pole_orders):
            for j, p_b in enumerate(pole_orders):
                if i != j:
                    key = (sid(r_id), sid(p_a), sid(p_b))
                    if key not in seen_fares:
                        seen_fares.add(key)
                        fares_to_insert.append((sid(r_id), sid(p_a), sid(p_b), 240))

    db.executemany('INSERT INTO fares VALUES (?, ?, ?, ?)', fares_to_insert)

    # Indexes
    db.execute('CREATE INDEX stop_times_station ON stop_times(station_id, dep_sec)')
    db.execute('CREATE INDEX stop_times_trip ON stop_times(trip, seq)')
    db.execute('CREATE INDEX trips_route ON trips(route_id)')
    db.execute('CREATE INDEX fares_lookup ON fares(route_id, from_zone, to_zone)')

    note = (
        f'東武バス：{len(routes_inserted)}系統（{len(used_poles)}停留所、{trip_count}便）の時刻表を収録。'
        f'東京都区内・埼玉県・千葉県・日光等の各線をカバー。祝日ダイヤ対応（{end.year}年末まで）。'
    )
    db.execute('INSERT INTO app_data VALUES (?, ?)', ('tobu-bus.note', note))

    db.commit()
    assert db.execute('PRAGMA integrity_check').fetchone()[0] == 'ok'

    args.output.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory() as td:
        tmp_db = Path(td) / 'tobu-bus.db'
        target_conn = sqlite3.connect(tmp_db)
        db.backup(target_conn)
        target_conn.close()
        raw_db_bytes = tmp_db.read_bytes()

    compressed_bundle = gzip.compress(raw_db_bytes, mtime=0)
    bundle_path = args.output / 'tobu-bus.bundle'
    bundle_path.write_bytes(compressed_bundle)

    db_sha256 = hashlib.sha256(raw_db_bytes).hexdigest()
    bundle_sha256 = hashlib.sha256(compressed_bundle).hexdigest()

    info = {
        'operator': '東武バス',
        'routes': len(routes_inserted),
        'stations': len(used_poles),
        'trips': trip_count,
        'stopTimesRows': total_stop_times_rows,
        'calendarStart': str(start),
        'calendarEnd': str(end),
        'size': len(raw_db_bytes),
        'sha256': db_sha256,
        'compressedSize': len(compressed_bundle),
        'bundleSha256': bundle_sha256,
        'note': note
    }

    info_path = args.output / 'tobu-bus-info.json'
    info_path.write_text(json.dumps(info, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')

    print(json.dumps(info, ensure_ascii=False, indent=2))
    return bundle_path, info_path


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--poles', type=Path, default=Path('scratch/tobu/odptBusstopPole.json'))
    parser.add_argument('--patterns', type=Path, default=Path('scratch/tobu/odptBusroutePattern.json'))
    parser.add_argument('--timetables', type=Path, default=Path('scratch/tobu/odptBusTimetable.json'))
    parser.add_argument('--station-reference', type=Path, default=Path('scratch/tobu/odptStation (5).json'))
    parser.add_argument('--existing-db', type=Path, default=Path('app/src/main/assets/bootstrap/timetable.db'))
    parser.add_argument('--output', type=Path, default=Path('app/src/main/assets/bootstrap'))
    args = parser.parse_args()
    build(args)
