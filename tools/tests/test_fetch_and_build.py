import gzip
import json
from pathlib import Path
import sqlite3
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
import sys
sys.path.insert(0, str(ROOT / 'tools'))
import fetch_and_build_transit_data as fb


class FetchAndBuildTest(unittest.TestCase):
    def setUp(self):
        self.temp_dir = tempfile.TemporaryDirectory()
        self.temp_path = Path(self.temp_dir.name)

    def tearDown(self):
        self.temp_dir.cleanup()

    def test_merge_seed_into_db_and_manifest_generation(self):
        base_db = ROOT / 'app/src/main/assets/bootstrap/timetable.db'
        bus_bundle = ROOT / 'app/src/main/assets/bootstrap/keio-bus.bundle'
        rail_bundle = ROOT / 'app/src/main/assets/bootstrap/keio.bundle'

        bus_seed = self.temp_path / 'keio-bus-seed.db'
        bus_seed.write_bytes(gzip.decompress(bus_bundle.read_bytes()))
        rail_seed = self.temp_path / 'keio-rail-seed.db'
        rail_seed.write_bytes(gzip.decompress(rail_bundle.read_bytes()))

        out_db = self.temp_path / 'timetable.db'
        stats = fb.build_integrated_timetable(
            base_timetable=base_db,
            keio_bus_seed_path=bus_seed,
            keio_bus_version='test-bus-version',
            keio_rail_seed_path=rail_seed,
            keio_rail_version='test-rail-version',
            output_db_path=out_db
        )

        self.assertGreaterEqual(stats['trips'], 60000)
        self.assertGreater(stats['valid_to'], 20261231)

        conn = sqlite3.connect(str(out_db))
        try:
            self.assertEqual(conn.execute('PRAGMA integrity_check').fetchone()[0], 'ok')
            bus_count = conn.execute("SELECT count(*) FROM trips WHERE substr(trip_id,1,14)='GTFS_KEIO_BUS:'").fetchone()[0]
            self.assertGreater(bus_count, 20000)
            rail_count = conn.execute("SELECT count(*) FROM trips WHERE substr(trip_id,1,10)='ODPT_KEIO:'").fetchone()[0]
            self.assertGreater(rail_count, 800)
            nt_count = conn.execute("SELECT count(*) FROM trips WHERE substr(trip_id,1,3)='NT_'").fetchone()[0]
            self.assertGreater(nt_count, 10000)
        finally:
            conn.close()

        # Test gzip compression & manifest
        gz_path = self.temp_path / 'timetable.db.gz'
        fb.compress_to_gzip(out_db, gz_path)
        self.assertTrue(gz_path.exists())
        self.assertLess(gz_path.stat().st_size, out_db.stat().st_size)

        manifest_path = self.temp_path / 'transit-manifest.json'
        manifest = fb.generate_manifest(
            output_manifest_path=manifest_path,
            db_gz_path=gz_path,
            raw_db_path=out_db,
            download_url_base='https://example.invalid/releases',
            valid_from=stats['valid_from'],
            valid_to=stats['valid_to'],
            trip_count=stats['trips'],
            release_version='test-release'
        )

        self.assertEqual(manifest['version'], 'test-release')
        self.assertEqual(len(manifest['files']), 1)
        self.assertEqual(manifest['files'][0]['id'], 'timetable')
        self.assertEqual(manifest['files'][0]['trip_count'], stats['trips'])
        self.assertTrue(manifest['files'][0]['sha256'])


if __name__ == '__main__':
    unittest.main()
