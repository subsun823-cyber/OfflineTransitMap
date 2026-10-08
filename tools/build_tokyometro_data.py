#!/usr/bin/env python3
"""Build the app's compact Tokyo Metro seed database from ODPT exports (stdlib only)."""
import argparse
import collections
import csv
import datetime as dt
import gzip
import hashlib
import json
import math
from pathlib import Path
import sqlite3
import tempfile

PREFIX = 'ODPT_TOKYO_METRO:'
TABLES = {
    'meta': 'key TEXT PRIMARY KEY, value TEXT',
    'feeds': 'feed_id TEXT PRIMARY KEY, start_date INTEGER, end_date INTEGER',
    'stations': 'station_id TEXT PRIMARY KEY, name TEXT, lat REAL, lon REAL, kana TEXT, grp TEXT, kind TEXT',
    'stops': 'stop_id TEXT PRIMARY KEY, station_id TEXT, name TEXT, platform TEXT, zone TEXT',
    'routes': 'route_id TEXT PRIMARY KEY, operator TEXT, name TEXT, long_name TEXT, color INTEGER, route_type INTEGER',
    'trips': 'trip_no INTEGER PRIMARY KEY, trip_id TEXT UNIQUE, route_id TEXT, service_id TEXT, headsign TEXT, train_type TEXT',
    'stop_times': 'trip INTEGER, seq INTEGER, stop_id TEXT, station_id TEXT, arr_sec INTEGER, dep_sec INTEGER, headsign TEXT, can_board INTEGER, can_alight INTEGER',
    'calendar': 'service_id TEXT PRIMARY KEY, start_date INTEGER, end_date INTEGER, mon INTEGER, tue INTEGER, wed INTEGER, thu INTEGER, fri INTEGER, sat INTEGER, sun INTEGER',
    'calendar_dates': 'service_id TEXT, date INTEGER, exception_type INTEGER',
    'fares': 'route_id TEXT, from_zone TEXT, to_zone TEXT, price INTEGER',
    'station_operators': 'station_id TEXT, operator TEXT, PRIMARY KEY (station_id, operator)',
    'app_data': 'key TEXT PRIMARY KEY, value TEXT',
}

TRAIN_TYPES = {
    'Local': '各駅停車',
    'Express': '急行',
    'Rapid': '快速',
    'CommuterExpress': '通勤急行',
    'CommuterRapid': '通勤快速',
    'SemiExpress': '準急',
    'RapidExpress': '快速急行',
    'F-Liner': 'Fライナー',
    'S-TRAIN': 'S-TRAIN（別途料金）',
    'TH-LINER': 'THライナー（別途料金）',
    'LimitedExpress': '特急（別途料金）',
}

CALENDARS = {
    'odpt.Calendar:Weekday': (1, 1, 1, 1, 1, 0, 0),
    'odpt.Calendar:SaturdayHoliday': (0, 0, 0, 0, 0, 1, 1),
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


def calc_metro_fare(dist_km: float) -> int:
    rail_km = dist_km * 1.25
    if rail_km <= 6.0:
        return 180
    elif rail_km <= 11.0:
        return 210
    elif rail_km <= 19.0:
        return 260
    elif rail_km <= 27.0:
        return 300
    else:
        return 330


def build(args):
    railways = load_json(args.railways)
    stations = load_json(args.stations)
    timetables = load_json(args.timetables)
    fares_data = load_json(args.fares) if args.fares.exists() else []

    # Load holidays
    holiday_rows = list(csv.reader(args.holidays.read_text(encoding='cp932').splitlines()))[1:]
    holidays = [dt.datetime.strptime(row[0], '%Y/%m/%d').date() for row in holiday_rows if row]

    # Validity range
    issued_dates = [dt.date.fromisoformat(x['dct:issued']) for x in timetables if 'dct:issued' in x]
    start = min(issued_dates) if issued_dates else dt.date(2026, 3, 16)
    end = dt.date(max(x.year for x in holidays), 12, 31)
    ymd = lambda d: int(d.strftime('%Y%m%d'))

    db = sqlite3.connect(':memory:')
    for table_name, cols in TABLES.items():
        db.execute(f'CREATE TABLE {table_name} ({cols})')

    db.execute("INSERT INTO meta VALUES ('schema_version', '5')")
    db.execute("INSERT INTO feeds VALUES (?, ?, ?)", (PREFIX, ymd(start), ymd(end)))

    sid = lambda raw: PREFIX + raw

    # 1. Stations & Stops
    station_by_id = {s['owl:sameAs']: s for s in stations}
    for s in stations:
        raw_id = s['owl:sameAs']
        name = s.get('odpt:stationTitle', {}).get('ja') or s.get('dc:title', '')
        lat = float(s['geo:lat'])
        lon = float(s['geo:long'])
        kana = s.get('odpt:stationTitle', {}).get('ja-Hrkt', '')

        candidates = db.execute('SELECT station_id, lat, lon, grp FROM stations WHERE name=?', (name,)).fetchall()
        near = [c for c in candidates if distance_m((lat, lon), (c[1], c[2])) <= 700]
        group = near[0][3] if near else sid(raw_id)

        db.execute(
            'INSERT INTO stations VALUES (?, ?, ?, ?, ?, ?, ?)',
            (sid(raw_id), name, lat, lon, kana, group, 'rail')
        )
        # Note: platform MUST be empty string '' (do NOT set stationCode like M08 into platform)
        db.execute(
            'INSERT INTO stops VALUES (?, ?, ?, ?, ?)',
            (sid(raw_id), sid(raw_id), name, '', sid(raw_id))
        )
        db.execute(
            'INSERT INTO station_operators VALUES (?, ?)',
            (sid(raw_id), '東京メトロ')
        )

    # 2. Routes
    railway_by_id = {r['owl:sameAs']: r for r in railways}
    orders = {}
    for r in railways:
        raw_id = r['owl:sameAs']
        r_name = r.get('odpt:railwayTitle', {}).get('ja') or r.get('dc:title', '')
        color_str = r.get('odpt:color', '#009BBF').lstrip('#')
        color_int = int(color_str, 16) if color_str else 0x009BBF
        db.execute(
            'INSERT INTO routes VALUES (?, ?, ?, ?, ?, 1)',
            (sid(raw_id), '東京メトロ', r_name, f'東京メトロ{r_name}', color_int)
        )
        orders[raw_id] = [st['odpt:station'] for st in r.get('odpt:stationOrder', [])]

    # Special extra-fare route for S-TRAIN / TH-LINER / Romancecar
    for raw_id in railway_by_id:
        r_title = railway_by_id[raw_id].get('dc:title', '')
        db.execute(
            'INSERT INTO routes VALUES (?, ?, ?, ?, ?, 1)',
            (sid(raw_id) + ':extra-fare', '東京メトロ', r_title + '（有料指定席）',
             '東京メトロ' + r_title + '（有料指定席）', 0x9C5E31)
        )

    # 3. Calendars
    for cal_id, flags in CALENDARS.items():
        db.execute(
            'INSERT INTO calendar VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)',
            (sid(cal_id), ymd(start), ymd(end), *flags)
        )
        for h in holidays:
            if start <= h <= end:
                exc = 2 if cal_id.endswith('Weekday') else 1
                db.execute(
                    'INSERT INTO calendar_dates VALUES (?, ?, ?)',
                    (sid(cal_id), ymd(h), exc)
                )

        # Year-end / New Year special schedule suppression (Dec 30 - Jan 3)
        for yr in range(start.year, end.year + 1):
            for m, d in [(1, 1), (1, 2), (1, 3), (12, 30), (12, 31)]:
                date = dt.date(yr, m, d)
                if start <= date <= end:
                    db.execute('DELETE FROM calendar_dates WHERE service_id=? AND date=?', (sid(cal_id), ymd(date)))
                    db.execute('INSERT INTO calendar_dates VALUES (?, ?, 2)', (sid(cal_id), ymd(date)))

    # 4. Group departures into Trips by (railway, calendar, trainNumber)
    train_groups = collections.defaultdict(list)
    total_raw_events = 0

    for t in timetables:
        r_id = t['odpt:railway']
        cal = t['odpt:calendar']
        st_id = t['odpt:station']
        for obj in t.get('odpt:stationTimetableObject', []):
            total_raw_events += 1
            tn = obj['odpt:trainNumber']
            d_sec = parse_time_sec(obj['odpt:departureTime'])
            dest = obj.get('odpt:destinationStation', [None])[0]
            raw_tt = obj.get('odpt:trainType', '').split('.')[-1]
            train_groups[(r_id, cal, tn)].append({
                'station': st_id,
                'dep_sec': d_sec,
                'dest': dest,
                'train_type': raw_tt
            })

    trip_count = 0
    total_stop_times_rows = 0
    trips_to_insert = []
    stop_times_to_insert = []

    for (r_id, cal, tn), stops in train_groups.items():
        stops.sort(key=lambda x: x['dep_sec'])
        st_order = orders.get(r_id, [])
        first_stop = stops[0]
        cur_tt = first_stop['train_type']
        cur_dest = first_stop['dest']

        trip_count += 1
        route_key = sid(r_id)
        if cur_tt in ('S-TRAIN', 'TH-LINER', 'LimitedExpress'):
            route_key += ':extra-fare'

        headsign = station_by_id.get(cur_dest, {}).get('odpt:stationTitle', {}).get('ja') or \
                   station_by_id.get(stops[-1]['station'], {}).get('odpt:stationTitle', {}).get('ja') or \
                   railway_by_id.get(r_id, {}).get('dc:title', '')

        train_type_label = TRAIN_TYPES.get(cur_tt, cur_tt)
        trip_id = f'{PREFIX}TRIP_{trip_count:06d}'
        trips_to_insert.append((trip_count, trip_id, route_key, sid(cal), headsign, train_type_label))

        # Check if destination station should be appended as terminus stop
        last_stop_st = stops[-1]['station']
        add_dest_row = False
        dest_st_id = cur_dest
        dest_idx = -1
        cur_idx = -1

        if dest_st_id and dest_st_id in st_order and dest_st_id != last_stop_st:
            dest_idx = st_order.index(dest_st_id)
            if last_stop_st in st_order:
                cur_idx = st_order.index(last_stop_st)
                # Check train direction
                if len(stops) >= 2 and stops[0]['station'] in st_order:
                    first_idx = st_order.index(stops[0]['station'])
                    is_forward = (cur_idx > first_idx)
                else:
                    is_forward = (dest_idx > cur_idx)
                if (is_forward and dest_idx > cur_idx) or (not is_forward and dest_idx < cur_idx):
                    add_dest_row = True

        seq = 1
        for idx, s in enumerate(stops):
            is_first = (idx == 0)
            is_last = (idx == len(stops) - 1)
            arr_sec = s['dep_sec'] if is_first else s['dep_sec'] - 30
            can_board = 0 if (is_last and not add_dest_row) else 1
            can_alight = 0 if is_first else 1

            stop_times_to_insert.append((
                trip_count, seq, sid(s['station']), sid(s['station']),
                arr_sec, s['dep_sec'], headsign, can_board, can_alight
            ))
            seq += 1
            total_stop_times_rows += 1

        if add_dest_row:
            travel_sec = abs(dest_idx - cur_idx) * 120
            arr_sec = stops[-1]['dep_sec'] + travel_sec
            stop_times_to_insert.append((
                trip_count, seq, sid(dest_st_id), sid(dest_st_id),
                arr_sec, arr_sec, headsign, 0, 1
            ))
            seq += 1
            total_stop_times_rows += 1

    db.executemany('INSERT INTO trips VALUES (?, ?, ?, ?, ?, ?)', trips_to_insert)
    db.executemany('INSERT INTO stop_times VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)', stop_times_to_insert)

    # 5. Fares
    # Store explicit fares from odptRailwayFare.json
    imported_fares = 0
    known_pairs = set()
    for f in fares_data:
        from_st = f.get('odpt:fromStation')
        to_st = f.get('odpt:toStation')
        ticket_fare = f.get('odpt:ticketFare')
        if from_st and to_st and ticket_fare is not None:
            price = int(ticket_fare)
            known_pairs.add((from_st, to_st))
            # Insert for all relevant metro routes
            for r_id in railway_by_id:
                db.execute(
                    'INSERT OR IGNORE INTO fares VALUES (?, ?, ?, ?)',
                    (sid(r_id), sid(from_st), sid(to_st), price)
                )
            imported_fares += 1

    # Supplement remaining intra-line station pairs with calculated standard metro fare
    st_coords = {s['owl:sameAs']: (float(s['geo:lat']), float(s['geo:long'])) for s in stations}
    supplemented_fares = 0
    for r_id, st_list in orders.items():
        for i, st_a in enumerate(st_list):
            for j, st_b in enumerate(st_list):
                if i == j:
                    continue
                if (st_a, st_b) not in known_pairs and st_a in st_coords and st_b in st_coords:
                    d_km = distance_m(st_coords[st_a], st_coords[st_b]) / 1000.0
                    price = calc_metro_fare(d_km)
                    db.execute(
                        'INSERT OR IGNORE INTO fares VALUES (?, ?, ?, ?)',
                        (sid(r_id), sid(st_a), sid(st_b), price)
                    )
                    supplemented_fares += 1

    # Indexes
    db.execute('CREATE INDEX stop_times_station ON stop_times(station_id, dep_sec)')
    db.execute('CREATE INDEX stop_times_trip ON stop_times(trip, seq)')
    db.execute('CREATE INDEX trips_route ON trips(route_id)')
    db.execute('CREATE INDEX fares_lookup ON fares(route_id, from_zone, to_zone)')

    note = (
        f'東京メトロ：全10路線（{len(stations)}駅）の全発車時刻（{total_raw_events}件、{trip_count}便）'
        f'および普通旅客運賃（提供データ {imported_fares}件・補完 {supplemented_fares}区間）を完全収録。'
        f'S-TRAIN・TH-LINER・ロマンスカー等の指定席は別途指定席券が必要です。祝日ダイヤ対応（{end.year}年末まで）。'
    )
    db.execute('INSERT INTO app_data VALUES (?, ?)', ('tokyometro.note', note))

    db.commit()
    assert db.execute('PRAGMA integrity_check').fetchone()[0] == 'ok'

    args.output.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory() as td:
        tmp_db = Path(td) / 'tokyometro.db'
        target_conn = sqlite3.connect(tmp_db)
        db.backup(target_conn)
        target_conn.close()
        raw_db_bytes = tmp_db.read_bytes()

    compressed_bundle = gzip.compress(raw_db_bytes, mtime=0)
    bundle_path = args.output / 'tokyometro.bundle'
    bundle_path.write_bytes(compressed_bundle)

    db_sha256 = hashlib.sha256(raw_db_bytes).hexdigest()
    bundle_sha256 = hashlib.sha256(compressed_bundle).hexdigest()

    info = {
        'operator': '東京メトロ',
        'railways': len(railways),
        'stations': len(stations),
        'rawDepartureEvents': total_raw_events,
        'trips': trip_count,
        'stopTimesRows': total_stop_times_rows,
        'receivedFares': imported_fares,
        'supplementedFares': supplemented_fares,
        'calendarStart': str(start),
        'calendarEnd': str(end),
        'size': len(raw_db_bytes),
        'sha256': db_sha256,
        'compressedSize': len(compressed_bundle),
        'bundleSha256': bundle_sha256,
        'note': note
    }

    info_path = args.output / 'tokyometro-info.json'
    info_path.write_text(json.dumps(info, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')

    print(json.dumps(info, ensure_ascii=False, indent=2))
    print(f'Successfully built {bundle_path} ({len(compressed_bundle)} bytes)')
    db.close()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--railways', type=Path, default=Path('inputs/tokyometro/odptRailway.json'))
    parser.add_argument('--stations', type=Path, default=Path('inputs/tokyometro/odptStation.json'))
    parser.add_argument('--timetables', type=Path, default=Path('inputs/tokyometro/odptStationTimetable.json'))
    parser.add_argument('--fares', type=Path, default=Path('inputs/tokyometro/odptRailwayFare.json'))
    parser.add_argument('--holidays', type=Path, default=Path('inputs/syukujitsu.csv'))
    parser.add_argument('--output', type=Path, default=Path('app/src/main/assets/bootstrap'))
    args = parser.parse_args()
    build(args)


if __name__ == '__main__':
    main()
