#!/usr/bin/env python3
"""Build the app's compact Tokyu Corporation seed database from ODPT exports (stdlib only)."""
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

PREFIX = 'ODPT_TOKYU:'
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

TRAIN_TYPES = {
    'Local': '各駅停車',
    'SemiExpress': '準急',
    'Express': '急行',
    'LimitedExpress': '特急',
    'CommuterLimitedExpress': '通勤特急',
    'F-Liner': 'Fライナー',
    'S-TRAIN': 'S-TRAIN（座席指定・別途料金）',
}

CALENDARS = {
    'odpt.Calendar:Weekday': (1, 1, 1, 1, 1, 0, 0),
    'odpt.Calendar:SaturdayHoliday': (0, 0, 0, 0, 0, 1, 1),
}

EXTERNAL_STATION_NAMES = {
    'odpt.Station:Minatomirai.Minatomirai.MotomachiChukagai': '元町・中華街',
    'odpt.Station:SaitamaRailway.SaitamaRailway.Hatogaya': '鳩ヶ谷',
    'odpt.Station:SaitamaRailway.SaitamaRailway.UrawaMisono': '浦和美園',
    'odpt.Station:Seibu.Ikebukuro.Hanno': '飯能',
    'odpt.Station:Seibu.Ikebukuro.Hoya': '保谷',
    'odpt.Station:Seibu.Ikebukuro.Kiyose': '清瀬',
    'odpt.Station:Seibu.Ikebukuro.Kotesashi': '小手指',
    'odpt.Station:Seibu.Ikebukuro.ShakujiiKoen': '石神井公園',
    'odpt.Station:Seibu.Ikebukuro.Tokorozawa': '所沢',
    'odpt.Station:Seibu.SeibuChichibu.SeibuChichibu': '西武秩父',
    'odpt.Station:Sotetsu.Izumino.Shonandai': '湘南台',
    'odpt.Station:Sotetsu.Main.Ebina': '海老名',
    'odpt.Station:Sotetsu.Main.Yamato': '大和',
    'odpt.Station:Sotetsu.SotetsuShinYokohama.Nishiya': '西谷',
    'odpt.Station:Tobu.Isesaki.Kuki': '久喜',
    'odpt.Station:Tobu.Nikko.MinamiKurihashi': '南栗橋',
    'odpt.Station:Tobu.TobuSkytree.KitaKoshigaya': '北越谷',
    'odpt.Station:Tobu.TobuSkytree.TobuDobutsuKoen': '東武動物公園',
    'odpt.Station:Tobu.Tojo.Kawagoeshi': '川越市',
    'odpt.Station:Tobu.Tojo.Ogawamachi': '小川町',
    'odpt.Station:Tobu.Tojo.Shiki': '志木',
    'odpt.Station:Tobu.Tojo.ShinrinKoen': '森林公園',
    'odpt.Station:Toei.Mita.NishiTakashimadaira': '西高島平',
    'odpt.Station:Toei.Mita.Takashimadaira': '高島平',
    'odpt.Station:TokyoMetro.Fukutoshin.Ikebukuro': '池袋',
    'odpt.Station:TokyoMetro.Fukutoshin.ShinjukuSanchome': '新宿三丁目',
    'odpt.Station:TokyoMetro.Fukutoshin.Wakoshi': '和光市',
    'odpt.Station:TokyoMetro.Hanzomon.AoyamaItchome': '青山一丁目',
    'odpt.Station:TokyoMetro.Hanzomon.Hanzomon': '半蔵門',
    'odpt.Station:TokyoMetro.Hanzomon.KiyosumiShirakawa': '清澄白河',
    'odpt.Station:TokyoMetro.Hanzomon.Nagatacho': '永田町',
    'odpt.Station:TokyoMetro.Hanzomon.Oshiage': '押上',
    'odpt.Station:TokyoMetro.Namboku.AkabaneIwabuchi': '赤羽岩淵',
    'odpt.Station:TokyoMetro.Namboku.Komagome': '駒込',
    'odpt.Station:TokyoMetro.Namboku.OjiKamiya': '王子神谷',
}

RAILWAY_COLORS = {
    'odpt.Railway:Tokyu.DenEnToshi': 0x00AA8E,
    'odpt.Railway:Tokyu.Ikegami': 0xEE86A7,
    'odpt.Railway:Tokyu.Kodomonokuni': 0x0071BE,
    'odpt.Railway:Tokyu.Meguro': 0x009CD3,
    'odpt.Railway:Tokyu.Oimachi': 0xF18C43,
    'odpt.Railway:Tokyu.Setagaya': 0xFCC80D,
    'odpt.Railway:Tokyu.TokyuShinYokohama': 0x890D84,
    'odpt.Railway:Tokyu.TokyuTamagawa': 0xAE0379,
    'odpt.Railway:Tokyu.Toyoko': 0xDB0442,
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


def calc_tokyu_fare(dist_km: float, is_setagaya: bool = False, is_kodomonokuni: bool = False) -> int:
    if is_setagaya:
        return 160
    if is_kodomonokuni:
        return 170
    rail_km = dist_km * 1.15
    if rail_km <= 3.0: return 140
    if rail_km <= 7.0: return 170
    if rail_km <= 11.0: return 210
    if rail_km <= 15.0: return 230
    if rail_km <= 20.0: return 270
    if rail_km <= 25.0: return 290
    if rail_km <= 30.0: return 330
    if rail_km <= 35.0: return 360
    if rail_km <= 40.0: return 400
    if rail_km <= 45.0: return 430
    if rail_km <= 50.0: return 470
    return 500


def build(args):
    railways = load_json(args.railways)
    stations = load_json(args.stations)
    timetables = load_json(args.timetables)
    fares_data = load_json(args.fares)

    # Japanese National Holidays through 2027
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

    start = dt.date(2026, 3, 14)
    end = dt.date(2027, 12, 31)
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
        kana = s.get('odpt:stationTitle', {}).get('ja-Hrkt', '') or s.get('odpt:kana', '')

        candidates = db.execute('SELECT station_id, lat, lon, grp FROM stations WHERE name=?', (name,)).fetchall()
        near = [c for c in candidates if distance_m((lat, lon), (c[1], c[2])) <= 700]
        group = near[0][3] if near else sid(raw_id)

        db.execute(
            'INSERT INTO stations VALUES (?, ?, ?, ?, ?, ?, ?)',
            (sid(raw_id), name, lat, lon, kana, group, 'rail')
        )
        db.execute(
            'INSERT INTO stops VALUES (?, ?, ?, ?, ?)',
            (sid(raw_id), sid(raw_id), name, '', sid(raw_id))
        )
        db.execute(
            'INSERT INTO station_operators VALUES (?, ?)',
            (sid(raw_id), '東急電鉄')
        )

    # 2. Routes
    railway_by_id = {r['owl:sameAs']: r for r in railways}
    orders = {}
    for r in railways:
        raw_id = r['owl:sameAs']
        r_name = r.get('odpt:railwayTitle', {}).get('ja') or r.get('dc:title', '')
        color_int = RAILWAY_COLORS.get(raw_id)
        if color_int is None:
            color_str = r.get('odpt:color', '#DB0442').lstrip('#')
            color_int = int(color_str, 16) if color_str else 0xDB0442

        db.execute(
            'INSERT INTO routes VALUES (?, ?, ?, ?, ?, 1)',
            (sid(raw_id), '東急電鉄', r_name, f'東急{r_name}', color_int)
        )
        orders[raw_id] = [st['odpt:station'] for st in r.get('odpt:stationOrder', [])]

    # Special extra-fare route for S-TRAIN
    for raw_id in railway_by_id:
        r_title = railway_by_id[raw_id].get('odpt:railwayTitle', {}).get('ja') or railway_by_id[raw_id].get('dc:title', '')
        db.execute(
            'INSERT INTO routes VALUES (?, ?, ?, ?, ?, 1)',
            (sid(raw_id) + ':extra-fare', '東急電鉄', r_title + '（有料指定席）',
             '東急' + r_title + '（有料指定席）', 0xD32F2F)
        )

    # 3. Calendars
    for cal_id, flags in CALENDARS.items():
        mon, tue, wed, thu, fri, sat, sun = flags
        db.execute(
            'INSERT INTO calendar (service_id, start_date, end_date, mon, tue, wed, thu, fri, sat, sun) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)',
            (sid(cal_id), ymd(start), ymd(end), mon, tue, wed, thu, fri, sat, sun)
        )
        for h in holidays:
            if start <= h <= end:
                exc = 2 if cal_id.endswith('Weekday') else 1
                db.execute(
                    'INSERT INTO calendar_dates VALUES (?, ?, ?)',
                    (sid(cal_id), ymd(h), exc)
                )

        # Year-end special schedule (Dec 30 - Jan 3)
        for yr in range(start.year, end.year + 1):
            for m, d in [(1, 1), (1, 2), (1, 3), (12, 30), (12, 31)]:
                date = dt.date(yr, m, d)
                if start <= date <= end:
                    db.execute('DELETE FROM calendar_dates WHERE service_id=? AND date=?', (sid(cal_id), ymd(date)))
                    db.execute('INSERT INTO calendar_dates VALUES (?, ?, 2)', (sid(cal_id), ymd(date)))

    # 4. Construct Trips & StopTimes from Station Timetables
    trip_count = 0
    total_raw_events = 0
    total_stop_times_rows = 0
    trips_to_insert = []
    stop_times_to_insert = []

    for r in railways:
        r_id = r['owl:sameAs']
        st_order = orders.get(r_id, [])

        for cal_id in CALENDARS:
            for direction in ['odpt.RailDirection:Outbound', 'odpt.RailDirection:Inbound']:
                dir_name = direction.split(':')[-1]
                if r_id == 'odpt.Railway:Tokyu.TokyuShinYokohama':
                    ordered_stations = list(st_order) if dir_name == 'Inbound' else list(reversed(st_order))
                else:
                    ordered_stations = list(st_order) if dir_name == 'Outbound' else list(reversed(st_order))

                # Gather all departures for each station along ordered_stations
                station_deps = {st_id: [] for st_id in ordered_stations}
                for t in timetables:
                    if t.get('odpt:railway') == r_id and t.get('odpt:calendar') == cal_id and t.get('odpt:railDirection') == direction:
                        st_id = t['odpt:station']
                        if st_id in station_deps:
                            for obj in t.get('odpt:stationTimetableObject', []):
                                total_raw_events += 1
                                d_sec = parse_time_sec(obj['odpt:departureTime'])
                                raw_tt = obj.get('odpt:trainType', '').split('.')[-1]
                                dest = obj.get('odpt:destinationStation', [None])[0]
                                station_deps[st_id].append({
                                    'sec': d_sec,
                                    'tt': raw_tt,
                                    'dest': dest,
                                    'used': False
                                })

                for st_id in ordered_stations:
                    station_deps[st_id].sort(key=lambda x: x['sec'])

                # Match consecutive stops into single trips
                for s_idx, st_id in enumerate(ordered_stations):
                    for dep in station_deps[st_id]:
                        if dep['used']:
                            continue

                        dep['used'] = True
                        trip_stops = [(st_id, dep)]
                        cur_sec = dep['sec']
                        cur_tt = dep['tt']
                        cur_dest = dep['dest']
                        last_stop_idx = s_idx

                        for next_s_idx in range(s_idx + 1, len(ordered_stations)):
                            next_st = ordered_stations[next_s_idx]
                            best_candidate = None
                            best_diff = 999999

                            for c in station_deps[next_st]:
                                if c['used']:
                                    continue
                                diff = c['sec'] - cur_sec
                                if 30 <= diff <= 1500:
                                    score = diff
                                    if c['dest'] != cur_dest:
                                        score += 10000
                                    if c['tt'] != cur_tt:
                                        score += 20000
                                    if score < best_diff:
                                        best_diff = score
                                        best_candidate = c

                            if best_candidate and best_diff < 15000:
                                best_candidate['used'] = True
                                trip_stops.append((next_st, best_candidate))
                                cur_sec = best_candidate['sec']
                                last_stop_idx = next_s_idx

                        # Check destination terminus append
                        last_st_id = trip_stops[-1][0]
                        add_dest = False
                        dest_arr_sec = trip_stops[-1][1]['sec'] + 120
                        if cur_dest and cur_dest != last_st_id and cur_dest in station_by_id:
                            add_dest = True
                            if cur_dest in ordered_stations:
                                dest_idx = ordered_stations.index(cur_dest)
                                if dest_idx > last_stop_idx:
                                    dist_stops = dest_idx - last_stop_idx
                                    dest_arr_sec = trip_stops[-1][1]['sec'] + max(120, dist_stops * 120)

                        if len(trip_stops) == 1 and not add_dest:
                            continue

                        trip_count += 1
                        route_key = sid(r_id)
                        if cur_tt == 'S-TRAIN':
                            route_key += ':extra-fare'

                        headsign = (
                            EXTERNAL_STATION_NAMES.get(cur_dest) or
                            station_by_id.get(cur_dest, {}).get('odpt:stationTitle', {}).get('ja') or
                            station_by_id.get(cur_dest, {}).get('dc:title') or
                            station_by_id.get(last_st_id, {}).get('odpt:stationTitle', {}).get('ja') or
                            '東急線'
                        )
                        train_type_label = TRAIN_TYPES.get(cur_tt, cur_tt)

                        trip_id = f'{PREFIX}TRIP_{trip_count:06d}'
                        trips_to_insert.append((trip_count, trip_id, route_key, sid(cal_id), headsign, train_type_label))

                        seq = 1
                        for idx, (stop_st_id, stop_dep) in enumerate(trip_stops):
                            is_first = (idx == 0)
                            is_last = (idx == len(trip_stops) - 1) and not add_dest
                            arr_sec = stop_dep['sec'] if is_first else stop_dep['sec'] - 30
                            can_board = 0 if is_last else 1
                            can_alight = 0 if is_first else 1

                            stop_times_to_insert.append((
                                trip_count, seq, sid(stop_st_id), sid(stop_st_id),
                                arr_sec, stop_dep['sec'], headsign, can_board, can_alight
                            ))
                            seq += 1
                            total_stop_times_rows += 1

                        if add_dest:
                            stop_times_to_insert.append((
                                trip_count, seq, sid(cur_dest), sid(cur_dest),
                                dest_arr_sec, dest_arr_sec, headsign, 0, 1
                            ))
                            seq += 1
                            total_stop_times_rows += 1

    db.executemany('INSERT INTO trips VALUES (?, ?, ?, ?, ?, ?)', trips_to_insert)
    db.executemany('INSERT INTO stop_times VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)', stop_times_to_insert)

    # 5. Fares
    imported_fares = 0
    known_pairs = set()
    for f in fares_data:
        from_st = f.get('odpt:fromStation')
        to_st = f.get('odpt:toStation')
        ticket_fare = f.get('odpt:ticketFare') or f.get('odpt:icCardFare')
        if from_st and to_st and ticket_fare is not None:
            price = int(ticket_fare)
            known_pairs.add((from_st, to_st))
            for r_id in railway_by_id:
                db.execute(
                    'INSERT OR IGNORE INTO fares VALUES (?, ?, ?, ?)',
                    (sid(r_id), sid(from_st), sid(to_st), price)
                )
            imported_fares += 1

    # Supplement remaining intra-line station pairs with calculated standard Tokyu fare
    st_coords = {s['owl:sameAs']: (float(s['geo:lat']), float(s['geo:long'])) for s in stations}
    supplemented_fares = 0
    for r_id, st_list in orders.items():
        is_setagaya = ('Setagaya' in r_id)
        is_kodomonokuni = ('Kodomonokuni' in r_id)
        for i, st_a in enumerate(st_list):
            for j, st_b in enumerate(st_list):
                if i == j:
                    continue
                if (st_a, st_b) not in known_pairs and st_a in st_coords and st_b in st_coords:
                    d_km = distance_m(st_coords[st_a], st_coords[st_b]) / 1000.0
                    price = calc_tokyu_fare(d_km, is_setagaya, is_kodomonokuni)
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
        f'東急電鉄：全9路線（{len(stations)}駅）の全発車時刻（{total_raw_events}件、{trip_count}便）'
        f'および普通旅客運賃（提供データ {imported_fares}件・補完 {supplemented_fares}区間）を完全収録。'
        f'S-TRAIN等の指定席は別途指定券が必要です。祝日ダイヤ対応（{end.year}年末まで）。'
    )
    db.execute('INSERT INTO app_data VALUES (?, ?)', ('tokyu.note', note))

    db.commit()
    assert db.execute('PRAGMA integrity_check').fetchone()[0] == 'ok'

    args.output.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory() as td:
        tmp_db = Path(td) / 'tokyu.db'
        target_conn = sqlite3.connect(tmp_db)
        db.backup(target_conn)
        target_conn.close()
        raw_db_bytes = tmp_db.read_bytes()

    compressed_bundle = gzip.compress(raw_db_bytes, mtime=0)
    bundle_path = args.output / 'tokyu.bundle'
    bundle_path.write_bytes(compressed_bundle)

    db_sha256 = hashlib.sha256(raw_db_bytes).hexdigest()
    bundle_sha256 = hashlib.sha256(compressed_bundle).hexdigest()

    info = {
        'operator': '東急電鉄',
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

    info_path = args.output / 'tokyu-info.json'
    info_path.write_text(json.dumps(info, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')

    print(json.dumps(info, ensure_ascii=False, indent=2))
    return bundle_path, info_path


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--railways', type=Path, default=Path('tokyu_extracted/odpt_Railway.json'))
    parser.add_argument('--stations', type=Path, default=Path('tokyu_extracted/odpt_Station.json'))
    parser.add_argument('--timetables', type=Path, default=Path('tokyu_extracted/odpt_StationTimetable (1).json'))
    parser.add_argument('--fares', type=Path, default=Path('tokyu_extracted/odpt_RailwayFare.json'))
    parser.add_argument('--output', type=Path, default=Path('app/src/main/assets/bootstrap'))
    args = parser.parse_args()
    build(args)
