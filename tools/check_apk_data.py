#!/usr/bin/env python3
"""Verify packaged data names/content after assembleDebug (AGP transforms .gz assets)."""
import argparse
import hashlib
import json
from pathlib import Path
import zipfile

ROOT=Path(__file__).resolve().parents[1]
def check(path):
    assets=ROOT/'app/src/main/assets'
    with zipfile.ZipFile(path) as apk:
        for name in ('keio.bundle','keio-info.json','keio-bus.bundle','keio-bus-info.json','odakyu.bundle','odakyu-info.json','tokyometro.bundle','tokyometro-info.json','seibu.bundle','seibu-info.json','data-files.json'):

            key='bootstrap/'+name
            if apk.read('assets/'+key)!=(assets/key).read_bytes():raise ValueError('APK asset mismatch: '+key)
        config=json.loads(apk.read('assets/bootstrap/data-files.json'))
        for entry in config['files']:
            if 'asset' not in entry:continue
            key='assets/'+entry['asset'];digest=hashlib.sha256()
            if apk.getinfo(key).file_size!=entry['size']:raise ValueError('APK size mismatch: '+key)
            with apk.open(key) as stream:
                for chunk in iter(lambda:stream.read(1024*1024),b''):digest.update(chunk)
            if digest.hexdigest()!=entry['sha256']:raise ValueError('APK hash mismatch: '+key)
    print('PASS: APK contains the exact seed, metadata, profile and configured bundled files.')
if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('apk',type=Path,nargs='?',default=ROOT/'app/build/outputs/apk/debug/app-debug.apk')
    check(parser.parse_args().apk)
