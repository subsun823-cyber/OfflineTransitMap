#!/usr/bin/env python3
"""Configure install-time bundled files or HTTPS downloads, using hashes of real files."""
import argparse
import hashlib
import json
import shutil
import sqlite3
from pathlib import Path
from urllib.parse import urlparse, quote

ROOT=Path(__file__).resolve().parents[1]

def configure(args):
    asset_root=ROOT/'app/src/main/assets'
    entries=[]
    for kind,filename in [('timetable','timetable.db'),('map','tokyo.pmtiles')]:
        source=getattr(args,kind)
        url=getattr(args,kind+'_url')
        if source is None:
            if url: raise ValueError('A local file is required to calculate the download hash')
            continue
        source=source.resolve()
        if kind=='map':
            with source.open('rb') as stream:
                if source.stat().st_size<127 or stream.read(8)!=b'PMTiles\x03':raise ValueError('Expected PMTiles v3')
        else:
            if Path(str(source)+'-wal').exists() and Path(str(source)+'-wal').stat().st_size:
                raise ValueError('Checkpoint/close the source database before distribution')
            db=sqlite3.connect('file:'+quote(str(source))+'?mode=ro',uri=True)
            try:
                if db.execute('PRAGMA quick_check').fetchone()[0]!='ok':raise ValueError('Invalid SQLite database')
                # Exact columns used by the app and Keio merge.
                for table,cols in {
                    'stations':'station_id,name,lat,lon,kana,grp,kind','stops':'stop_id,station_id,name,platform,zone',
                    'routes':'route_id,operator,name,long_name,color,route_type','trips':'trip_no,trip_id,route_id,service_id,headsign,train_type',
                    'stop_times':'trip,seq,stop_id,station_id,arr_sec,dep_sec,headsign,can_board,can_alight',
                    'calendar':'service_id,start_date,end_date,mon,tue,wed,thu,fri,sat,sun',
                    'calendar_dates':'service_id,date,exception_type','fares':'route_id,from_zone,to_zone,price',
                    'meta':'key,value','feeds':'start_date,end_date'
                }.items():db.execute(f'SELECT {cols} FROM {table} LIMIT 0')
            finally:db.close()
        digest=hashlib.sha256()
        with source.open('rb') as stream:
            for chunk in iter(lambda:stream.read(1024*1024),b''):digest.update(chunk)
        entry={'id':kind,'size':source.stat().st_size,'sha256':digest.hexdigest()}
        if url:
            parsed=urlparse(url)
            if parsed.scheme!='https' or not parsed.netloc or parsed.username or parsed.password:raise ValueError('Use a public HTTPS download URL without credentials')
            entry['url']=url
        else:
            destination=asset_root/'bootstrap'/filename
            if source!=destination.resolve():shutil.copyfile(source,destination)
            entry['asset']='bootstrap/'+filename
        entries.append(entry)
    if not entries:raise ValueError('Specify --timetable and/or --map')
    path=asset_root/'bootstrap/data-files.json'
    path.write_text(json.dumps({'files':entries},ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
    print(f'Configured {len(entries)} files in {path.relative_to(ROOT)}')

if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__)
    for name in ('timetable','map'):
        p.add_argument('--'+name,type=Path)
        p.add_argument('--'+name+'-url')
    configure(p.parse_args())
