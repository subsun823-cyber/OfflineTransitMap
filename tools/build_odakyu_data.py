#!/usr/bin/env python3
"""Build the app's compact Odakyu seed database from ODPT exports (stdlib only)."""
import argparse
import bisect
import csv
import datetime as dt
import gzip
import hashlib
import json
import math
from pathlib import Path
import sqlite3
import tempfile

PREFIX = 'ODPT_ODAKYU:'
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
    'SemiExpress': '準急',
    'CommuterSemiExpress': '通勤準急',
    'Express': '急行',
    'CommuterExpress': '通勤急行',
    'RapidExpress': '快速急行',
    'LimitedExpress': '特急ロマンスカー（別途料金）',
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


def build(args):
    railways = load_json(args.railways)
    stations = load_json(args.stations)
    timetables = load_json(args.timetables)

    # Load holidays
    holiday_rows = list(csv.reader(args.holidays.read_text(encoding='cp932').splitlines()))[1:]
    holidays = [dt.datetime.strptime(row[0], '%Y/%m/%d').date() for row in holiday_rows if row]

    # Validity range
    issued_dates = [dt.date.fromisoformat(x['dct:issued']) for x in timetables if 'dct:issued' in x]
    start = min(issued_dates) if issued_dates else dt.date(2026, 3, 14)
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
        db.execute(
            'INSERT INTO stops VALUES (?, ?, ?, ?, ?)',
            (sid(raw_id), sid(raw_id), name, s.get('odpt:stationCode', ''), sid(raw_id))
        )
        db.execute(
            'INSERT INTO station_operators VALUES (?, ?)',
            (sid(raw_id), '小田急電鉄')
        )

    # 2. Routes
    railway_by_id = {r['owl:sameAs']: r for r in railways}
    orders = {}
    for r in railways:
        raw_id = r['owl:sameAs']
        r_name = r.get('odpt:railwayTitle', {}).get('ja') or r.get('dc:title', '')
        color_str = r.get('odpt:color', '#0085CE').lstrip('#')
        color_int = int(color_str, 16) if color_str else 0x0085CE
        db.execute(
            'INSERT INTO routes VALUES (?, ?, ?, ?, ?, 2)',
            (sid(raw_id), '小田急電鉄', r_name, f'小田急{r_name}', color_int)
        )
        orders[raw_id] = [st['odpt:station'] for st in r.get('odpt:stationOrder', [])]

    # Special extra-fare route for Romancecar (LimitedExpress)
    for raw_id in railway_by_id:
        db.execute(
            'INSERT INTO routes VALUES (?, ?, ?, ?, ?, 2)',
            (sid(raw_id) + ':extra-fare', '小田急電鉄', railway_by_id[raw_id].get('dc:title', '') + '（特急）',
             '小田急' + railway_by_id[raw_id].get('dc:title', '') + '（特急ロマンスカー）', 0x0085CE)
        )

    # 3. Calendars
    for cal_id, flags in CALENDARS.items():
        db.execute(
            'INSERT INTO calendar VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)',
            (sid(cal_id), ymd(start), ymd(end), *flags)
        )
        for h in holidays:
            if start <= h <= end:
                # SaturdayHoliday runs on holidays (1: add), Weekday stops (2: remove)
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

    # 4. Group departures and construct Trips & StopTimes
    deps_by_group = {}
    total_raw_events = 0

    for t in timetables:
        st_id = t['odpt:station']
        r_id = t['odpt:railway']
        r_dir = t['odpt:railDirection']
        cal = t['odpt:calendar']
        group_key = (r_id, r_dir, cal)
        if group_key not in deps_by_group:
            deps_by_group[group_key] = {}
        if st_id not in deps_by_group[group_key]:
            deps_by_group[group_key][st_id] = []

        for obj in t.get('odpt:stationTimetableObject', []):
            total_raw_events += 1
            d_sec = parse_time_sec(obj['odpt:departureTime'])
            dest = obj.get('odpt:destinationStation', [None])[0]
            tt = obj.get('odpt:trainType', '').split('.')[-1]
            deps_by_group[group_key][st_id].append({
                'dep_sec': d_sec,
                'train_type': tt,
                'dest': dest,
                'is_origin': obj.get('odpt:isOrigin', False),
                'used': False,
                'station_id': st_id
            })

    for group_key in deps_by_group:
        for st_id in deps_by_group[group_key]:
            deps_by_group[group_key][st_id].sort(key=lambda x: x['dep_sec'])

    trip_count = 0
    total_stop_times_rows = 0

    trips_to_insert = []
    stop_times_to_insert = []

    for group_key, st_map in deps_by_group.items():
        r_id, r_dir, cal = group_key
        st_order = orders[r_id]
        if r_dir == 'odpt.RailDirection:Inbound':
            st_order = list(reversed(st_order))

        # Fast lookup by pre-extracting departure seconds per station
        st_sec_lists = {st_id: [x['dep_sec'] for x in st_map.get(st_id, [])] for st_id in st_order}

        for start_idx, st_id in enumerate(st_order):
            for dep in st_map.get(st_id, []):
                if dep['used']:
                    continue

                dep['used'] = True
                cur_sec = dep['dep_sec']
                cur_tt = dep['train_type']
                cur_dest = dep['dest']

                chain = [(st_id, cur_sec, dep)]
                cur_idx = start_idx

                # Trace downstream stations with bisect
                while cur_idx < len(st_order) - 1:
                    matched_next = None
                    matched_next_idx = None

                    # Search up to 10 stations ahead for express skips
                    for next_idx in range(cur_idx + 1, min(len(st_order), cur_idx + 10)):
                        next_st = st_order[next_idx]
                        dist_idx = next_idx - cur_idx
                        min_sec = cur_sec + dist_idx * 45
                        max_sec = cur_sec + dist_idx * 300 + 180

                        sec_list = st_sec_lists[next_st]
                        cands = st_map.get(next_st, [])
                        pos_start = bisect.bisect_left(sec_list, min_sec)
                        for pos in range(pos_start, len(sec_list)):
                            if sec_list[pos] > max_sec:
                                break
                            cand = cands[pos]
                            if not cand['used'] and cand['train_type'] == cur_tt and cand['dest'] == cur_dest:
                                matched_next = cand
                                matched_next_idx = next_idx
                                break
                        if matched_next:
                            break

                    if matched_next:
                        matched_next['used'] = True
                        chain.append((st_order[matched_next_idx], matched_next['dep_sec'], matched_next))
                        cur_sec = matched_next['dep_sec']
                        cur_idx = matched_next_idx
                        if st_order[matched_next_idx] == cur_dest:
                            break
                    else:
                        break

                trip_count += 1
                route_key = sid(r_id)
                if cur_tt == 'LimitedExpress':
                    route_key += ':extra-fare'

                headsign = station_by_id.get(cur_dest, {}).get('odpt:stationTitle', {}).get('ja') or \
                           station_by_id.get(chain[-1][0], {}).get('odpt:stationTitle', {}).get('ja') or '小田急線'
                train_type_label = TRAIN_TYPES.get(cur_tt, cur_tt)

                trip_id = f'{PREFIX}TRIP_{trip_count:06d}'
                trips_to_insert.append((trip_count, trip_id, route_key, sid(cal), headsign, train_type_label))

                # Append downstream terminus if chain ended before destination station
                dest_st_id = cur_dest
                add_dest_row = False
                if dest_st_id and dest_st_id in st_order and dest_st_id != chain[-1][0]:
                    dest_idx = st_order.index(dest_st_id)
                    if dest_idx > cur_idx:
                        add_dest_row = True

                seq = 1
                for idx, (stop_st_id, d_sec, d_obj) in enumerate(chain):
                    is_first = (idx == 0)
                    is_last_in_chain = (idx == len(chain) - 1)
                    arr_sec = d_sec if is_first else d_sec - 30
                    can_board = 0 if (is_last_in_chain and not add_dest_row) else 1
                    can_alight = 0 if is_first else 1

                    stop_times_to_insert.append((
                        trip_count, seq, sid(stop_st_id), sid(stop_st_id),
                        arr_sec, d_sec, headsign, can_board, can_alight
                    ))
                    seq += 1
                    total_stop_times_rows += 1

                if add_dest_row:
                    travel_sec = (dest_idx - cur_idx) * 120
                    arr_sec = cur_sec + travel_sec
                    stop_times_to_insert.append((
                        trip_count, seq, sid(dest_st_id), sid(dest_st_id),
                        arr_sec, arr_sec, headsign, 0, 1
                    ))
                    seq += 1
                    total_stop_times_rows += 1

    db.executemany('INSERT INTO trips VALUES (?, ?, ?, ?, ?, ?)', trips_to_insert)
    db.executemany('INSERT INTO stop_times VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)', stop_times_to_insert)

    # Indexes
    db.execute('CREATE INDEX stop_times_station ON stop_times(station_id, dep_sec)')
    db.execute('CREATE INDEX stop_times_trip ON stop_times(trip, seq)')
    db.execute('CREATE INDEX trips_route ON trips(route_id)')

    note = f'小田急電鉄：全線（小田原線・江ノ島線・多摩線 {len(stations)}駅）の全発車時刻（{total_raw_events}件、{trip_count}便）を収録。運賃データは未提供のため0円または未表示となります。特急ロマンスカーは別途特急券が必要です。祝日ダイヤ対応（{end.year}年末まで）。'
    db.execute('INSERT INTO app_data VALUES (?, ?)', ('odakyu.note', note))

    db.commit()
    assert db.execute('PRAGMA integrity_check').fetchone()[0] == 'ok'

    args.output.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory() as td:
        tmp_db = Path(td) / 'odakyu.db'
        target_conn = sqlite3.connect(tmp_db)
        db.backup(target_conn)
        target_conn.close()
        raw_db_bytes = tmp_db.read_bytes()

    compressed_bundle = gzip.compress(raw_db_bytes, mtime=0)
    bundle_path = args.output / 'odakyu.bundle'
    bundle_path.write_bytes(compressed_bundle)

    db_sha256 = hashlib.sha256(raw_db_bytes).hexdigest()
    bundle_sha256 = hashlib.sha256(compressed_bundle).hexdigest()

    info = {
        'operator': '小田急電鉄',
        'railways': len(railways),
        'stations': len(stations),
        'rawDepartureEvents': total_raw_events,
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

    info_path = args.output / 'odakyu-info.json'
    info_path.write_text(json.dumps(info, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')

    print(json.dumps(info, ensure_ascii=False, indent=2))
    print(f'Successfully built {bundle_path} ({len(compressed_bundle)} bytes)')
    db.close()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--railways', type=Path, default=Path('inputs/odakyu/odptRailway.json'))
    parser.add_argument('--stations', type=Path, default=Path('inputs/odakyu/odptStation.json'))
    parser.add_argument('--timetables', type=Path, default=Path('inputs/odakyu/odptStationTimetable.json'))
    parser.add_argument('--holidays', type=Path, default=Path('inputs/syukujitsu.csv'))
    parser.add_argument('--output', type=Path, default=Path('app/src/main/assets/bootstrap'))
    args = parser.parse_args()
    build(args)


if __name__ == '__main__':
    main()
