#!/usr/bin/env python3
"""Build the app's compact Keio seed from user-supplied ODPT exports (stdlib only)."""
import argparse
import csv
import datetime as dt
import gzip
import hashlib
import json
import math
import sqlite3
import tempfile
from pathlib import Path

PREFIX = 'ODPT_KEIO:'
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
TRAIN_TYPES = {'Local': '各駅停車', 'Express': '急行', 'SemiExpress': '区間急行', 'LimitedExpress': '特急', 'KeioLiner': '京王ライナー（別途料金）'}
CALENDARS = {'odpt.Calendar:Weekday': (1,1,1,1,1,0,0), 'odpt.Calendar:SaturdayHoliday': (0,0,0,0,0,1,1)}

def load(path):
    data = json.loads(path.read_text(encoding='utf-8-sig'))
    assert isinstance(data, list) and data, f'Empty export: {path}'
    assert all(x.get('odpt:operator') == 'odpt.Operator:Keio' for x in data), f'Unexpected operator: {path}'
    assert len({x['owl:sameAs'] for x in data}) == len(data), f'Duplicate IDs: {path}'
    return data

def seconds(value, previous=0):
    hour, minute = map(int, value.split(':'))
    assert 0 <= hour < 48 and 0 <= minute < 60
    result = hour * 3600 + minute * 60
    # ODPT service day extends through the following early morning.
    if hour < 3: result += 86400
    if result < previous: result += 86400
    assert previous <= result < 172800, 'Invalid timetable order'
    return result

def distance(a, b):
    return math.hypot((a[0]-b[0])*110540, (a[1]-b[1])*111320*math.cos(math.radians(a[0])))

def build(args):
    railways, stations, trains, fares = [load(p) for p in (args.railways,args.stations,args.trains,args.fares)]
    station_by_id = {x['owl:sameAs']: x for x in stations}
    railway_by_id = {x['owl:sameAs']: x for x in railways}
    assert all(x['odpt:railway'] in railway_by_id for x in stations)
    # The published holiday CSV defines the calendar coverage, not the timetable's validity.
    holiday_rows = list(csv.reader(args.holidays.read_text(encoding='cp932').splitlines()))[1:]
    holidays = [dt.datetime.strptime(row[0], '%Y/%m/%d').date() for row in holiday_rows if row]
    start = min(dt.date.fromisoformat(x['dct:issued']) for x in trains)
    end = dt.date(max(x.year for x in holidays),12,31)
    assert start <= end
    ymd = lambda day: int(day.strftime('%Y%m%d'))
    db = sqlite3.connect(':memory:')
    for name, columns in TABLES.items(): db.execute(f'CREATE TABLE {name} ({columns})')
    db.execute("INSERT INTO meta VALUES ('schema_version','5')")
    def sid(raw): return PREFIX + raw
    for station in stations:
        raw=station['owl:sameAs']; name=station['dc:title'];lat=float(station['geo:lat']);lon=float(station['geo:long'])
        assert -90<=lat<=90 and -180<=lon<=180
        candidates=db.execute('SELECT station_id,lat,lon,grp FROM stations WHERE name=?',(name,)).fetchall()
        near=[x for x in candidates if distance((lat,lon),(x[1],x[2])) <=700]
        group=near[0][3] if near else sid(raw)
        db.execute('INSERT INTO stations VALUES (?,?,?,?,?,?,?)',(sid(raw),name,lat,lon,station.get('odpt:stationTitle',{}).get('ja-Hrkt',''),group,'rail'))
        db.execute('INSERT INTO stops VALUES (?,?,?,?,?)',(sid(raw),sid(raw),name,'',sid(raw)))
        db.execute('INSERT INTO station_operators VALUES (?,?)',(sid(raw),'京王電鉄'))
    for raw,line in railway_by_id.items():
        db.execute('INSERT INTO routes VALUES (?,?,?,?,?,2)',(sid(raw),'京王電鉄',line['dc:title'],line['dc:title'],int(line['odpt:color'].lstrip('#'),16)))
    for raw,flags in CALENDARS.items():
        db.execute('INSERT INTO calendar VALUES (?,?,?,?,?,?,?,?,?,?)',(sid(raw),ymd(start),ymd(end),*flags))
        for day in holidays:
            if start<=day<=end:
                db.execute('INSERT INTO calendar_dates VALUES (?,?,?)',(sid(raw),ymd(day),2 if raw.endswith('Weekday') else 1))
        # 年末年始の特別ダイヤは未提供。平日扱いで誤案内しないよう全便を抑止。
        for year in range(start.year,end.year+1):
            for month,day in [(1,1),(1,2),(1,3),(12,30),(12,31)]:
                date=dt.date(year,month,day)
                if start<=date<=end:
                    db.execute('DELETE FROM calendar_dates WHERE service_id=? AND date=?',(sid(raw),ymd(date)))
                    db.execute('INSERT INTO calendar_dates VALUES (?,?,2)',(sid(raw),ymd(date)))
    skipped=0;count=0
    for train in trains:
        cal=train['odpt:calendar']
        if cal not in CALENDARS: skipped+=1;continue
        raw_route=train['odpt:railway'];assert raw_route in railway_by_id
        route=sid(raw_route);kind=train['odpt:trainType'].split('.')[-1]
        assert kind in TRAIN_TYPES, f'Unknown train type {kind}'
        if kind=='KeioLiner':
            # Extra-fare services use a separate fare key so the base ticket is not quoted as a total.
            route += ':extra-fare'
            db.execute('INSERT OR IGNORE INTO routes SELECT ?,operator,name,long_name,color,route_type FROM routes WHERE route_id=?',(route,sid(raw_route)))
        rows=[];previous=0
        for seq,item in enumerate(train['odpt:trainTimetableObject'],1):
            raw=item.get('odpt:departureStation') or item['odpt:arrivalStation']
            assert raw in station_by_id, f'Missing station {raw}'
            if item.get('odpt:arrivalStation'): assert item['odpt:arrivalStation']==raw
            arr=seconds(item.get('odpt:arrivalTime') or item['odpt:departureTime'],previous)
            dep=seconds(item.get('odpt:departureTime') or item['odpt:arrivalTime'],arr)
            previous=dep
            rows.append((seq,sid(raw),sid(raw),arr,dep,None,int('odpt:departureTime' in item),int(seq>1)))
        assert len(rows)>=2
        count+=1
        dest=train['odpt:destinationStation'][0]
        headsign=station_by_id.get(dest,{}).get('dc:title') or station_by_id[(train['odpt:trainTimetableObject'][-1].get('odpt:arrivalStation') or train['odpt:trainTimetableObject'][-1]['odpt:departureStation'])]['dc:title']
        db.execute('INSERT INTO trips VALUES (?,?,?,?,?,?)',(count,sid(train['owl:sameAs']),route,sid(cal),headsign,TRAIN_TYPES[kind]))
        db.executemany('INSERT INTO stop_times VALUES (?,?,?,?,?,?,?,?,?)',[(count,*row) for row in rows])
    imported_fares=0
    for fare in fares:
        a,b=fare['odpt:fromStation'],fare['odpt:toStation']
        assert a in station_by_id and b in station_by_id
        price=fare['odpt:ticketFare'];assert isinstance(price,int) and price>=0
        # Standard fare lookup is per route; endpoint zones remain station IDs.
        for raw_route in railway_by_id:
            db.execute('INSERT INTO fares VALUES (?,?,?,?)',(sid(raw_route),sid(a),sid(b),price))
        imported_fares+=1
    for name,cols in [('stop_times_station','station_id,dep_sec'),('stop_times_trip','trip,seq')]: db.execute(f'CREATE INDEX {name} ON stop_times({cols})')
    db.execute('CREATE INDEX fares_lookup ON fares(route_id,from_zone,to_zone)')
    note=f'京王は部分データです（通常日 {count}便、競馬開催日 {skipped}便は日程未提供のため対象外）。高尾線・相模原線等の時刻表と一部運賃が未収録です。適用終了日の記載はありません。祝日対応は{end.year}年まで。年末年始の特別ダイヤ・直通便の接続は未対応です。'
    db.execute('INSERT INTO app_data VALUES (?,?)',('keio.note',note))
    report={'stations':len(stations),'stationGroups':db.execute('SELECT COUNT(DISTINCT grp) FROM stations').fetchone()[0],'railways':len(railways),'receivedTrains':len(trains),'importedTrains':count,'specialCalendarTrainsExcluded':skipped,'receivedFares':imported_fares,'calendarStart':str(start),'calendarEnd':str(end),'sourceTimetableDate':max(x['dc:date'] for x in trains),'note':note,'sources':{p.name:hashlib.sha256(p.read_bytes()).hexdigest() for p in (args.railways,args.stations,args.trains,args.fares,args.holidays)}}
    db.commit();assert db.execute('PRAGMA integrity_check').fetchone()[0]=='ok'
    args.output.mkdir(parents=True,exist_ok=True)
    with tempfile.TemporaryDirectory() as directory:
        file=Path(directory)/'keio.db'
        with sqlite3.connect(file) as target: db.backup(target)
        raw=file.read_bytes()
    compressed=gzip.compress(raw,mtime=0)
    (args.output/'keio.bundle').write_bytes(compressed)
    report['sha256']=hashlib.sha256(raw).hexdigest();report['size']=len(raw)
    (args.output/'keio-info.json').write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
    print(json.dumps({k:v for k,v in report.items() if k!='sources'},ensure_ascii=False,indent=2));print('Compressed bytes:',len(compressed))
    db.close()

if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__)
    for name in ['railways','stations','trains','fares','holidays','output']:p.add_argument('--'+name,type=Path,required=True)
    build(p.parse_args())
