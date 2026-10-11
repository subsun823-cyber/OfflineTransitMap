import gzip
import hashlib
import importlib.util
import json
from pathlib import Path
import sqlite3
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
spec_rail = importlib.util.spec_from_file_location('builder_rail', ROOT / 'tools/build_tokyu_data.py')
builder_rail = importlib.util.module_from_spec(spec_rail)
spec_rail.loader.exec_module(builder_rail)


class TokyuRailSeedTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.info = json.loads((ROOT / 'app/src/main/assets/bootstrap/tokyu-info.json').read_text(encoding='utf-8'))
        raw = gzip.decompress((ROOT / 'app/src/main/assets/bootstrap/tokyu.bundle').read_bytes())
        self.assertEqual(self.info['size'], len(raw))
        self.assertEqual(self.info['sha256'], hashlib.sha256(raw).hexdigest())
        path = Path(self.temp.name) / 'tokyu_seed.db'
        path.write_bytes(raw)
        self.db = sqlite3.connect(path)

    def tearDown(self):
        self.db.close()
        self.temp.cleanup()

    def count(self, sql):
        return self.db.execute(sql).fetchone()[0]

    def test_counts_and_no_orphans(self):
        self.assertEqual('ok', self.db.execute('PRAGMA integrity_check').fetchone()[0])
        self.assertEqual(self.info['stations'], self.count('SELECT COUNT(*) FROM stations'))
        self.assertEqual(self.info['trips'], self.count('SELECT COUNT(*) FROM trips'))
        self.assertEqual(self.info['stopTimesRows'], self.count('SELECT COUNT(*) FROM stop_times'))
        for table, key in [('trips', 'trip_no'), ('stations', 'station_id'), ('stops', 'stop_id')]:
            foreign = 'trip' if table == 'trips' else key
            self.assertEqual(0, self.count(
                f'SELECT COUNT(*) FROM stop_times s LEFT JOIN {table} t ON s.{foreign}=t.{key} WHERE t.{key} IS NULL'))
        self.assertEqual(0, self.count('SELECT COUNT(*) FROM (SELECT trip,seq,COUNT(*) n FROM stop_times GROUP BY trip,seq HAVING n>1)'))

    def test_all_stations_have_operator_and_major_stations_exist(self):
        self.assertEqual(self.info['stations'], self.count("SELECT COUNT(*) FROM station_operators WHERE operator='東急電鉄'"))

        # Major Tokyu stations exist
        for st_name in ['渋谷', '中目黒', '自由が丘', '武蔵小杉', '日吉', '横浜', '三軒茶屋', '二子玉川', '溝の口', '中央林間', '大井町', '蒲田', '新横浜']:
            st_rows = self.db.execute("SELECT name, lat, lon FROM stations WHERE name=?", (st_name,)).fetchall()
            self.assertGreaterEqual(len(st_rows), 1, f"Expected station {st_name} to exist")
            self.assertGreater(st_rows[0][1], 35.0)
            self.assertGreater(st_rows[0][2], 139.0)

    def test_routes_and_trips(self):
        self.assertEqual(self.info['railways'] * 2, self.count("SELECT COUNT(*) FROM routes WHERE operator='東急電鉄'"))
        trips_count = self.count("SELECT COUNT(*) FROM trips")
        self.assertGreater(trips_count, 5000)

    def test_icon_exists(self):
        icon_file = ROOT / 'app/src/main/res/drawable-xxxhdpi/map_station_tokyu.png'
        self.assertTrue(icon_file.exists())
        self.assertGreater(icon_file.stat().st_size, 500)


if __name__ == '__main__':
    unittest.main()
