#!/usr/bin/env python3
"""Convert the supplied Keio bus GTFS into the existing v5 bootstrap format.

No live API/key is required. Unsupported feeds fail explicitly; path-dependent
fares are omitted instead of being quoted as ordinary origin/destination fares.
"""
import argparse
import collections
import csv
from decimal import Decimal
import gzip
import hashlib
import io
import json
from pathlib import Path
import sqlite3
import tempfile
import zipfile

from build_keio_data import TABLES, distance

PREFIX = 'GTFS_KEIO_BUS:'


def seconds(value):
    h, m, s = map(int, value.split(':'))
    if not (0 <= h < 48 and 0 <= m < 60 and 0 <= s < 60):
        raise ValueError('Invalid GTFS time: ' + value)
    return h * 3600 + m * 60 + s


def fare_rows(rules, attributes, route_zones):
    # The app has no contains_id/path fare dimension. Exclude affected routes.
    excluded = {r['route_id'] for r in rules if r['contains_id']}
    prices = collections.defaultdict(set)
    for rule in rules:
        route = rule['route_id']
        if not route or route not in route_zones:
            raise ValueError('Expected an explicit, known fare route')
        if route in excluded:
            continue
        fare = attributes[rule['fare_id']]
        price = Decimal(fare['price'])
        if fare['currency_type'] != 'JPY' or price < 0 or price != int(price) or fare['transfers'] != '0':
            raise ValueError('Unsupported fare attributes')
        origins = [rule['origin_id']] if rule['origin_id'] else route_zones[route]
        destinations = [rule['destination_id']] if rule['destination_id'] else route_zones[route]
        for a in origins:
            for b in destinations:
                if a and b:
                    prices[(route, a, b)].add(int(price))
    ambiguous = sum(len(values) != 1 for values in prices.values())
    rows = [(PREFIX+r, PREFIX+a, PREFIX+b, next(iter(values)))
            for (r, a, b), values in sorted(prices.items()) if len(values) == 1]
    return rows, sorted(excluded), ambiguous


def build(source, output):
    db = sqlite3.connect(':memory:')
    for name, columns in TABLES.items():
        db.execute(f'CREATE TABLE {name} ({columns})')
    db.execute("INSERT INTO meta VALUES ('schema_version','5')")
    sid = lambda value: PREFIX + value
    with zipfile.ZipFile(source) as archive:
        def rows(name):
            with archive.open(name + '.txt') as stream:
                yield from csv.DictReader(io.TextIOWrapper(stream, encoding='utf-8-sig', newline=''))
        agencies = list(rows('agency'))
        if len(agencies) != 1 or agencies[0]['agency_name'] != '京王電鉄バス株式会社' or agencies[0]['agency_timezone'] != 'Asia/Tokyo':
            raise ValueError('Expected supplied Keio bus Japan feed')
        feed, = rows('feed_info')
        start, end = int(feed['feed_start_date']), int(feed['feed_end_date'])
        if start > end:
            raise ValueError('Invalid feed dates')
        db.execute('INSERT INTO feeds VALUES (?,?,?)', (PREFIX, start, end))
        kana = {}
        for row in rows('translations'):
            if row['table_name'] == 'stops' and row['field_name'] == 'stop_name' and row['language'] == 'ja-Hrkt':
                kana[row['record_id'] or row['field_value']] = row['translation']
        stops = {r['stop_id']: r for r in rows('stops')}
        by_name = collections.defaultdict(list)
        for raw, stop in stops.items():
            if stop['location_type'] not in ('', '0') or stop['parent_station']:
                raise ValueError('This feed converter expects unparented bus platforms')
            lat, lon = float(stop['stop_lat']), float(stop['stop_lon'])
            if not (-90 <= lat <= 90 and -180 <= lon <= 180):
                raise ValueError('Invalid stop coordinates')
            name = stop['stop_name']
            nearby = [s for s in by_name[name] if distance((lat,lon),s[:2]) <= 700]
            group = nearby[0][2] if nearby else sid(raw)
            by_name[name].append((lat,lon,group))
            db.execute('INSERT INTO stations VALUES (?,?,?,?,?,?,?)', (sid(raw),name,lat,lon,kana.get(raw,kana.get(name,'')),group,'bus'))
            db.execute('INSERT INTO stops VALUES (?,?,?,?,?)', (sid(raw),sid(raw),name,stop['platform_code'],sid(stop['zone_id']) if stop['zone_id'] else None))
        route_zones = collections.defaultdict(set)
        for row in rows('routes'):
            if row['route_type'] != '3' or row['agency_id'] != agencies[0]['agency_id']:
                raise ValueError('Expected Keio bus routes')
            db.execute('INSERT INTO routes VALUES (?,?,?,?,?,3)', (sid(row['route_id']),'京王バス',row['route_short_name'],row['route_long_name'],int(row['route_color'] or '34659B',16)))
        services = set()
        for row in rows('calendar'):
            flags = [int(row[day]) for day in ('monday','tuesday','wednesday','thursday','friday','saturday','sunday')]
            if any(flag not in (0,1) for flag in flags): raise ValueError('Invalid calendar flag')
            services.add(row['service_id'])
            db.execute('INSERT INTO calendar VALUES (?,?,?,?,?,?,?,?,?,?)',(sid(row['service_id']),int(row['start_date']),int(row['end_date']),*flags))
        for row in rows('calendar_dates'):
            if row['exception_type'] not in ('1','2'): raise ValueError('Invalid exception')
            services.add(row['service_id'])
            db.execute('INSERT INTO calendar_dates VALUES (?,?,?)',(sid(row['service_id']),int(row['date']),int(row['exception_type'])))
        trips = {}
        for number, row in enumerate(rows('trips'),1):
            if row['trip_id'] in trips or row['service_id'] not in services: raise ValueError('Invalid trip/service')
            trips[row['trip_id']] = (number,row['route_id'])
            db.execute('INSERT INTO trips VALUES (?,?,?,?,?,?)',(number,sid(row['trip_id']),sid(row['route_id']),sid(row['service_id']),row['trip_headsign'] or None,''))
        for row in rows('stop_times'):
            number, route = trips[row['trip_id']]
            stop = stops[row['stop_id']]
            arr, dep = seconds(row['arrival_time']), seconds(row['departure_time'])
            if arr > dep or row['pickup_type'] not in ('','0','1') or row['drop_off_type'] not in ('','0','1'):
                raise ValueError('Unsupported stop time / reservation-only service')
            db.execute('INSERT INTO stop_times VALUES (?,?,?,?,?,?,?,?,?)', (number,int(row['stop_sequence']),sid(row['stop_id']),sid(row['stop_id']),arr,dep,row['stop_headsign'] or None,int(row['pickup_type'] != '1'),int(row['drop_off_type'] != '1')))
            if stop['zone_id']: route_zones[route].add(stop['zone_id'])
        db.execute('CREATE UNIQUE INDEX stop_times_trip ON stop_times(trip,seq)')
        db.execute('CREATE INDEX stop_times_station ON stop_times(station_id,dep_sec)')
        # Trips in this feed have empty headsigns; retain per-stop headsign, then terminal fallback.
        db.execute('UPDATE trips SET headsign=(SELECT s.name FROM stop_times st JOIN stops s USING(stop_id) WHERE st.trip=trips.trip_no ORDER BY seq DESC LIMIT 1) WHERE headsign IS NULL')
        for number, in db.execute('SELECT trip_no FROM trips'):
            previous = -1
            for arr,dep in db.execute('SELECT arr_sec,dep_sec FROM stop_times WHERE trip=? ORDER BY seq',(number,)):
                if arr < previous: raise ValueError('Times run backwards')
                previous = dep
        fares, excluded, ambiguous = fare_rows(list(rows('fare_rules')), {r['fare_id']:r for r in rows('fare_attributes')}, route_zones)
        db.executemany('INSERT INTO fares VALUES (?,?,?,?)', fares)
        db.execute('CREATE UNIQUE INDEX fares_lookup ON fares(route_id,from_zone,to_zone)')
    note = f'京王バスの有効期間: {start//10000}/{start//100%100}/{start%100}〜{end//10000}/{end//100%100}/{end%100}。経由地条件のある{len(excluded)}系統の運賃は未対応です。'
    db.execute('INSERT INTO app_data VALUES (?,?)',('keio-bus.note',note))
    for table,key,parent,parent_key in [('trips','route_id','routes','route_id'),('stop_times','trip','trips','trip_no'),('stop_times','stop_id','stops','stop_id')]:
        if db.execute(f'SELECT 1 FROM {table} a LEFT JOIN {parent} b ON a.{key}=b.{parent_key} WHERE b.{parent_key} IS NULL LIMIT 1').fetchone(): raise ValueError('Orphan data: '+table)
    db.commit()
    report = {'prefix':PREFIX,'sourceSha256':hashlib.sha256(source.read_bytes()).hexdigest(),'feedVersion':feed['feed_version'],'startDate':start,'endDate':end,'note':note,'excludedFareRoutes':excluded,'ambiguousFarePairs':ambiguous,'counts':{t:db.execute(f'SELECT COUNT(*) FROM {t}').fetchone()[0] for t in TABLES},'stationGroups':db.execute('SELECT COUNT(DISTINCT grp) FROM stations').fetchone()[0]}
    with tempfile.TemporaryDirectory() as directory:
        file=Path(directory)/'seed.db'
        with sqlite3.connect(file) as target: db.backup(target)
        raw=file.read_bytes()
    db.close()
    report.update(size=len(raw),sha256=hashlib.sha256(raw).hexdigest())
    output.mkdir(parents=True,exist_ok=True)
    (output/'keio-bus.bundle').write_bytes(gzip.compress(raw,mtime=0))
    (output/'keio-bus-info.json').write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
    print(json.dumps(report,ensure_ascii=False,indent=2))


if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('gtfs',type=Path)
    parser.add_argument('--output',type=Path,default=Path('app/src/main/assets/bootstrap'))
    args=parser.parse_args()
    build(args.gtfs,args.output)
