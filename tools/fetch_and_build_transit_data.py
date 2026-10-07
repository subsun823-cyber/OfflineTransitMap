#!/usr/bin/env python3
"""Automated pipeline to fetch latest GTFS data, build SQLite bundles, merge into complete timetable.db, and generate distribution manifest."""

import argparse
import datetime as dt
import gzip
import hashlib
import io
import json
import os
from pathlib import Path
import shutil
import sqlite3
import sys
import urllib.request
import urllib.error

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / 'tools'))

import build_keio_bus_data
import build_keio_data

DEFAULT_MANIFEST_NAME = "transit-manifest.json"
DEFAULT_RELEASE_TAG = "transit-data-latest"


def compute_sha256_and_size(path: Path):
    digest = hashlib.sha256()
    size = 0
    with path.open('rb') as f:
        while True:
            chunk = f.read(1024 * 1024)
            if not chunk:
                break
            size += len(chunk)
            digest.update(chunk)
    return digest.hexdigest(), size


def merge_seed_into_db(target_db_path: Path, seed_db_path: Path, prefix: str, version: str, version_key: str):
    """Replicates KeioDatabase.merge logic in pure Python sqlite3."""
    conn = sqlite3.connect(str(target_db_path))
    try:
        conn.execute("ATTACH DATABASE ? AS seed", (str(seed_db_path),))
        conn.execute("BEGIN TRANSACTION")

        conn.execute("CREATE TABLE IF NOT EXISTS station_operators (station_id TEXT, operator TEXT, PRIMARY KEY(station_id,operator))")
        conn.execute("CREATE TABLE IF NOT EXISTS app_data (key TEXT PRIMARY KEY, value TEXT)")

        # Remove previously merged rows for this prefix
        owned = f"substr(trip_id, 1, {len(prefix)}) = '{prefix}'"
        conn.execute(f"DELETE FROM stop_times WHERE trip IN (SELECT trip_no FROM trips WHERE {owned})")
        conn.execute(f"DELETE FROM trips WHERE {owned}")
        for table, key in [
            ("stops", "stop_id"),
            ("stations", "station_id"),
            ("routes", "route_id"),
            ("calendar", "service_id"),
            ("calendar_dates", "service_id"),
            ("fares", "route_id"),
            ("station_operators", "station_id")
        ]:
            conn.execute(f"DELETE FROM {table} WHERE substr({key}, 1, {len(prefix)}) = '{prefix}'")

        offset = conn.execute("SELECT COALESCE(MAX(trip_no), 0) FROM trips").fetchone()[0]

        columns = {
            "stations": "station_id,name,lat,lon,kana,grp,kind",
            "stops": "stop_id,station_id,name,platform,zone",
            "routes": "route_id,operator,name,long_name,color,route_type",
            "trips": "trip_no,trip_id,route_id,service_id,headsign,train_type",
            "stop_times": "trip,seq,stop_id,station_id,arr_sec,dep_sec,headsign,can_board,can_alight",
            "calendar": "service_id,start_date,end_date,mon,tue,wed,thu,fri,sat,sun",
            "calendar_dates": "service_id,date,exception_type",
            "fares": "route_id,from_zone,to_zone,price"
        }

        for table, names in columns.items():
            cols = names.split(',')
            selected = ','.join(f"{c} + {offset}" if (table == "trips" and c == "trip_no") or (table == "stop_times" and c == "trip") else c for c in cols)
            conn.execute(f"INSERT INTO {table} ({names}) SELECT {selected} FROM seed.{table}")

        conn.execute("INSERT INTO station_operators SELECT station_id, operator FROM seed.station_operators")

        # Group merging (700m rule)
        import math
        for s_id, s_name, s_lat, s_lon, s_grp, s_kind in conn.execute("SELECT station_id, name, lat, lon, grp, kind FROM seed.stations").fetchall():
            group = s_grp
            nearest = 700.0
            candidates = conn.execute(
                f"SELECT COALESCE(grp, station_id), lat, lon FROM stations WHERE kind=? AND name=? AND substr(station_id, 1, {len(prefix)}) != '{prefix}'",
                (s_kind, s_name)
            ).fetchall()
            for c_grp, c_lat, c_lon in candidates:
                dlat = (s_lat - c_lat) * 110540
                dlon = (s_lon - c_lon) * 111320 * math.cos(math.radians(s_lat))
                meters = math.hypot(dlat, dlon)
                if meters <= nearest:
                    nearest = meters
                    group = c_grp
            conn.execute("UPDATE stations SET grp=? WHERE station_id=?", (group, s_id))

        conn.execute("INSERT OR REPLACE INTO app_data SELECT key, value FROM seed.app_data")
        conn.execute("INSERT OR REPLACE INTO app_data VALUES (?, ?)", (version_key, version))

        # Feed table handling
        feed_cols = [c[1] for c in conn.execute("PRAGMA main.table_info(feeds)").fetchall()]
        feed_key = "prefix" if "prefix" in feed_cols else "feed_id"
        for f_id, f_start, f_end in conn.execute("SELECT feed_id, start_date, end_date FROM seed.feeds").fetchall():
            conn.execute(f"INSERT OR REPLACE INTO feeds ({feed_key}, start_date, end_date) VALUES (?, ?, ?)", (f_id, f_start, f_end))

        conn.commit()
    finally:
        conn.close()


def download_if_modified(url: str, dest_path: Path, cached_etag: str = "", cached_sha256: str = "") -> tuple[bool, str, str]:
    """Downloads url to dest_path if modified. Returns (was_modified, new_etag, new_sha256)."""
    req = urllib.request.Request(url, headers={'User-Agent': 'OfflineTransitMap-DataUpdater/1.0'})
    if cached_etag:
        req.add_header('If-None-Match', cached_etag)

    try:
        with urllib.request.urlopen(req, timeout=60) as resp:
            content = resp.read()
            sha256 = hashlib.sha256(content).hexdigest()
            if sha256 == cached_sha256 and dest_path.exists():
                return False, cached_etag, cached_sha256
            dest_path.parent.mkdir(parents=True, exist_ok=True)
            dest_path.write_bytes(content)
            etag = resp.headers.get('ETag', '')
            return True, etag, sha256
    except urllib.error.HTTPError as e:
        if e.code == 304:
            return False, cached_etag, cached_sha256
        raise


def download_odpt_gtfs(token: str, operator_code: str, dest_path: Path, is_challenge: bool = False) -> bool:
    """Downloads GTFS zip from ODPT API or Challenge API using consumer key token."""
    if not token:
        return False
    candidates = []
    if is_challenge:
        candidates.extend([
            f"https://api-challenge.odpt.org/api/v4/files/odpt/{operator_code}/GTFS.zip?acl:consumerKey={token}",
            f"https://api-challenge.odpt.org/api/v4/files/odpt/{operator_code}/gtfs.zip?acl:consumerKey={token}",
            f"https://api-challenge.odpt.org/api/v4/files/{operator_code}/GTFS.zip?acl:consumerKey={token}"
        ])
    else:
        candidates.extend([
            f"https://api-public.odpt.org/api/v4/files/odpt/{operator_code}/GTFS.zip?acl:consumerKey={token}",
            f"https://api.odpt.org/api/v4/files/odpt/{operator_code}/GTFS.zip?acl:consumerKey={token}",
            f"https://api-public.odpt.org/api/v4/files/odpt/{operator_code}/gtfs.zip?acl:consumerKey={token}"
        ])
    for url in candidates:
        try:
            req = urllib.request.Request(url, headers={'User-Agent': 'OfflineTransitMap-DataUpdater/1.0'})
            with urllib.request.urlopen(req, timeout=90) as resp:
                if resp.status == 200:
                    dest_path.parent.mkdir(parents=True, exist_ok=True)
                    dest_path.write_bytes(resp.read())
                    endpoint_type = "Challenge 2026" if is_challenge else "ODPT Standard"
                    print(f"Downloaded {operator_code} GTFS from {endpoint_type} API ({dest_path.stat().st_size} bytes)")
                    return True
        except Exception as e:
            print(f"Notice: Could not fetch {operator_code} from {url.split('?')[0]}: {e}")
            continue
    return False


def resolve_base_db(base_db_arg: Path, base_db_url: str, download_url_base: str, work_dir: Path) -> Path:
    """Finds or downloads the base timetable DB."""
    if base_db_arg and base_db_arg.exists():
        print(f"Using local base timetable: {base_db_arg}")
        return base_db_arg

    work_dir.mkdir(parents=True, exist_ok=True)
    target_db = work_dir / "base-timetable.db"

    candidate_urls = []
    if base_db_url:
        candidate_urls.append(base_db_url)
    if download_url_base:
        candidate_urls.append(f"{download_url_base.rstrip('/')}/timetable.db.gz")
        candidate_urls.append(f"{download_url_base.rstrip('/')}/timetable.db")

    for url in candidate_urls:
        print(f"Attempting to fetch base timetable from {url}...")
        try:
            req = urllib.request.Request(url, headers={'User-Agent': 'OfflineTransitMap-DataUpdater/1.0'})
            with urllib.request.urlopen(req, timeout=120) as resp:
                if resp.status == 200:
                    data = resp.read()
                    if url.endswith('.gz') or (len(data) >= 2 and data[:2] == b'\x1f\x8b'):
                        decompressed = gzip.decompress(data)
                        target_db.write_bytes(decompressed)
                    else:
                        target_db.write_bytes(data)
                    print(f"Successfully obtained base timetable ({target_db.stat().st_size} bytes)")
                    return target_db
        except Exception as e:
            print(f"Notice: Could not fetch from {url}: {e}")

    raise FileNotFoundError(
        f"Base timetable DB not found at '{base_db_arg}' and could not be fetched from {candidate_urls}.\n"
        "For the initial automated build, please either create a GitHub release 'transit-data-latest' with timetable.db.gz,\n"
        "or pass --base-db-url pointing to a valid timetable.db."
    )


def build_integrated_timetable(
    base_timetable: Path,
    keio_bus_seed_path: Path | None,
    keio_bus_version: str,
    keio_rail_seed_path: Path | None,
    keio_rail_version: str,
    output_db_path: Path
):
    """Takes base timetable.db (JR + Nishi Tokyo Bus) and merges Keio train and Keio bus seeds."""
    output_db_path.parent.mkdir(parents=True, exist_ok=True)
    if output_db_path.resolve() != base_timetable.resolve():
        shutil.copyfile(base_timetable, output_db_path)

    # 1. Merge Keio Rail if seed available
    if keio_rail_seed_path and keio_rail_seed_path.exists():
        print(f"Merging Keio Rail seed into {output_db_path}...")
        merge_seed_into_db(
            output_db_path,
            keio_rail_seed_path,
            prefix="ODPT_KEIO:",
            version=keio_rail_version,
            version_key="keio.version"
        )
    else:
        print("Notice: Keio Rail seed not provided; keeping existing rail records.")

    # 2. Merge Keio Bus if seed available
    if keio_bus_seed_path and keio_bus_seed_path.exists():
        print(f"Merging Keio Bus seed into {output_db_path}...")
        merge_seed_into_db(
            output_db_path,
            keio_bus_seed_path,
            prefix="GTFS_KEIO_BUS:",
            version=keio_bus_version,
            version_key="keio-bus.version"
        )
    else:
        print("Notice: Keio Bus seed not provided; keeping existing bus records.")

    # 3. Integrity & summary
    conn = sqlite3.connect(str(output_db_path))
    try:
        check = conn.execute("PRAGMA integrity_check").fetchone()[0]
        if check != 'ok':
            raise ValueError(f"Integrity check failed: {check}")
        
        trip_count = conn.execute("SELECT count(*) FROM trips").fetchone()[0]
        stop_count = conn.execute("SELECT count(*) FROM stops").fetchone()[0]
        min_start, max_end = conn.execute("SELECT MIN(start_date), MAX(end_date) FROM feeds").fetchone()
    finally:
        conn.close()

    return {
        "trips": trip_count,
        "stops": stop_count,
        "valid_from": min_start,
        "valid_to": max_end
    }


def compress_to_gzip(source_file: Path, target_gz_path: Path):
    target_gz_path.parent.mkdir(parents=True, exist_ok=True)
    with source_file.open('rb') as f_in, target_gz_path.open('wb') as f_out:
        with gzip.GzipFile(filename='', mode='wb', fileobj=f_out, mtime=0) as gz_out:
            shutil.copyfileobj(f_in, gz_out, length=1024 * 1024)


def generate_manifest(
    output_manifest_path: Path,
    db_gz_path: Path,
    raw_db_path: Path,
    download_url_base: str,
    valid_from: int,
    valid_to: int,
    trip_count: int,
    release_version: str = ""
):
    gz_sha256, gz_size = compute_sha256_and_size(db_gz_path)
    raw_sha256, raw_size = compute_sha256_and_size(raw_db_path)

    if not release_version:
        release_version = dt.datetime.now(dt.timezone.utc).strftime("%Y%m%d-%H%M")

    vf_str = f"{valid_from // 10000}/{valid_from // 100 % 100:02d}/{valid_from % 100:02d}" if valid_from else ""
    vt_str = f"{valid_to // 10000}/{valid_to // 100 % 100:02d}/{valid_to % 100:02d}" if valid_to else ""

    manifest = {
        "schema_version": 1,
        "version": release_version,
        "updated_at": dt.datetime.now(dt.timezone.utc).isoformat(),
        "files": [
            {
                "id": "timetable",
                "name": "統合時刻表データ（JR東日本・京王電鉄・京王バス・西東京バス）",
                "filename": db_gz_path.name,
                "url": f"{download_url_base.rstrip('/')}/{db_gz_path.name}",
                "compressed": "gzip",
                "size": gz_size,
                "sha256": gz_sha256,
                "uncompressed_size": raw_size,
                "uncompressed_sha256": raw_sha256,
                "valid_from": valid_from,
                "valid_to": valid_to,
                "validity_text": f"{vf_str}〜{vt_str}" if vf_str and vt_str else "",
                "trip_count": trip_count
            }
        ]
    }

    output_manifest_path.parent.mkdir(parents=True, exist_ok=True)
    output_manifest_path.write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding='utf-8')
    return manifest


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--base-db', type=Path, default=ROOT / 'app/src/main/assets/bootstrap/timetable.db',
                        help='Base timetable.db (containing JR & Nishi Tokyo Bus)')
    parser.add_argument('--base-db-url', type=str, default=os.environ.get('BASE_TIMETABLE_URL', ''),
                        help='URL to download base timetable DB if local file not found')
    parser.add_argument('--keio-bus-gtfs', type=Path, help='Path to latest Keio Bus GTFS zip')
    parser.add_argument('--keio-bus-bundle', type=Path, default=ROOT / 'app/src/main/assets/bootstrap/keio-bus.bundle',
                        help='Pre-built Keio Bus bundle (if GTFS zip not specified)')
    parser.add_argument('--keio-rail-bundle', type=Path, default=ROOT / 'app/src/main/assets/bootstrap/keio.bundle',
                        help='Keio Rail bundle')
    parser.add_argument('--output-dir', type=Path, default=ROOT / 'build/transit-dist',
                        help='Output directory for generated release artifacts')
    parser.add_argument('--download-url-base', type=str,
                        default='https://github.com/subsun823-cyber/OfflineTransitMap/releases/download/transit-data-latest',
                        help='Base URL where release artifacts will be hosted')
    parser.add_argument('--odpt-token', type=str, default=os.environ.get('ODPT_ACCESS_TOKEN', ''),
                        help='ODPT API consumer key (token) for fetching bus GTFS (e.g. Nishi Tokyo Bus)')
    parser.add_argument('--odpt-challenge-token', type=str, default=os.environ.get('ODPT_CHALLENGE_TOKEN', ''),
                        help='ODPT Challenge 2026 API consumer key for railway GTFS (JR East, Keio Rail, etc.)')
    parser.add_argument('--version-tag', type=str, default='',
                        help='Release version tag (default: timestamp)')

    args = parser.parse_args()
    args.output_dir.mkdir(parents=True, exist_ok=True)

    # 1. Resolve base timetable.db
    resolved_base_db = resolve_base_db(
        base_db_arg=args.base_db,
        base_db_url=args.base_db_url,
        download_url_base=args.download_url_base,
        work_dir=args.output_dir
    )

    # 2. Resolve Keio Bus seed
    keio_bus_seed_file = args.output_dir / "keio-bus-seed.db"
    keio_gtfs_target = args.keio_bus_gtfs
    if not keio_gtfs_target:
        odpt_download_path = args.output_dir / "keio-bus-odpt.zip"
        # Try Challenge 2026 token first, then Standard ODPT token
        if args.odpt_challenge_token and download_odpt_gtfs(args.odpt_challenge_token, "KeioBus", odpt_download_path, is_challenge=True):
            keio_gtfs_target = odpt_download_path
        elif args.odpt_token and download_odpt_gtfs(args.odpt_token, "KeioBus", odpt_download_path, is_challenge=False):
            keio_gtfs_target = odpt_download_path

    bus_version = ""
    if keio_gtfs_target and keio_gtfs_target.exists():
        print(f"Building Keio Bus bundle from GTFS: {keio_gtfs_target}...")
        build_keio_bus_data.build(keio_gtfs_target, args.output_dir)
        raw_bus = gzip.decompress((args.output_dir / 'keio-bus.bundle').read_bytes())
        keio_bus_seed_file.write_bytes(raw_bus)
        bus_info = json.loads((args.output_dir / 'keio-bus-info.json').read_text(encoding='utf-8'))
        bus_version = bus_info['sha256']
    elif args.keio_bus_bundle and args.keio_bus_bundle.exists():
        print(f"Using pre-built Keio Bus bundle: {args.keio_bus_bundle}...")
        raw_bus = gzip.decompress(args.keio_bus_bundle.read_bytes())
        keio_bus_seed_file.write_bytes(raw_bus)
        info_path = ROOT / 'app/src/main/assets/bootstrap/keio-bus-info.json'
        if info_path.exists():
            bus_info = json.loads(info_path.read_text(encoding='utf-8'))
            bus_version = bus_info.get('sha256', '')
        else:
            bus_version = hashlib.sha256(raw_bus).hexdigest()
    else:
        print("Notice: No Keio Bus bundle or GTFS found; existing bus records in base DB will be retained.")
        keio_bus_seed_file = None

    # 3. Resolve Keio Rail seed
    keio_rail_seed_file = args.output_dir / "keio-rail-seed.db"
    rail_version = ""
    if args.keio_rail_bundle and args.keio_rail_bundle.exists():
        raw_rail = gzip.decompress(args.keio_rail_bundle.read_bytes())
        keio_rail_seed_file.write_bytes(raw_rail)
        rail_info_path = ROOT / 'app/src/main/assets/bootstrap/keio-info.json'
        if rail_info_path.exists():
            rail_info = json.loads(rail_info_path.read_text(encoding='utf-8'))
            rail_version = rail_info.get('sha256', '')
        else:
            rail_version = hashlib.sha256(raw_rail).hexdigest()
    else:
        print("Notice: No Keio Rail bundle found; existing rail records in base DB will be retained.")
        keio_rail_seed_file = None

    # 4. Build integrated DB
    output_db = args.output_dir / "timetable.db"
    print(f"Integrating into {output_db}...")
    stats = build_integrated_timetable(
        base_timetable=resolved_base_db,
        keio_bus_seed_path=keio_bus_seed_file,
        keio_bus_version=bus_version,
        keio_rail_seed_path=keio_rail_seed_file,
        keio_rail_version=rail_version,
        output_db_path=output_db
    )
    print(f"Integrated DB ready: {stats['trips']} trips, validity {stats['valid_from']}..{stats['valid_to']}")

    # 4. Compress to gzip
    output_gz = args.output_dir / "timetable.db.gz"
    print(f"Compressing {output_db} to {output_gz}...")
    compress_to_gzip(output_db, output_gz)

    # 5. Generate manifest
    manifest_file = args.output_dir / DEFAULT_MANIFEST_NAME
    manifest = generate_manifest(
        output_manifest_path=manifest_file,
        db_gz_path=output_gz,
        raw_db_path=output_db,
        download_url_base=args.download_url_base,
        valid_from=stats['valid_from'],
        valid_to=stats['valid_to'],
        trip_count=stats['trips'],
        release_version=args.version_tag
    )
    print(f"Generated manifest: {manifest_file}")
    print(json.dumps(manifest, ensure_ascii=False, indent=2))


if __name__ == '__main__':
    main()
