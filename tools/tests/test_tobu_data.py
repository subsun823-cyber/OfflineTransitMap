import gzip
import hashlib
import importlib.util
import json
from pathlib import Path
import sqlite3
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
spec_rail = importlib.util.spec_from_file_location('builder_rail', ROOT / 'tools/build_tobu_data.py')
builder_rail = importlib.util.module_from_spec(spec_rail)
spec_rail.loader.exec_module(builder_rail)

spec_bus = importlib.util.spec_from_file_location('builder_bus', ROOT / 'tools/build_tobu_bus_data.py')
builder_bus = importlib.util.module_from_spec(spec_bus)
spec_bus.loader.exec_module(builder_bus)


class TobuRailSeedTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.info = json.loads((ROOT / 'app/src/main/assets/bootstrap/tobu-info.json').read_text(encoding='utf-8'))
        raw = gzip.decompress((ROOT / 'app/src/main/assets/bootstrap/tobu.bundle').read_bytes())
        self.assertEqual(self.info['size'], len(raw))
        self.assertEqual(self.info['sha256'], hashlib.sha256(raw).hexdigest())
        path = Path(self.temp.name) / 'tobu_seed.db'
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
        self.assertEqual(self.info['stations'], self.count("SELECT COUNT(*) FROM station_operators WHERE operator='東武鉄道'"))

        # Major Tobu stations exist
        for st_name in ['浅草', '北千住', 'とうきょうスカイツリー', '柏', '大宮', '川越', '池袋', '東武日光']:
            st_rows = self.db.execute("SELECT name, lat, lon FROM stations WHERE name=?", (st_name,)).fetchall()
            self.assertGreaterEqual(len(st_rows), 1, f"Expected station {st_name} to exist")
            self.assertGreater(st_rows[0][1], 35.0)
            self.assertGreater(st_rows[0][2], 139.0)

    def test_routes_and_special_express(self):
        self.assertEqual(self.info['railways'] * 2, self.count("SELECT COUNT(*) FROM routes WHERE operator='東武鉄道'"))
        extra_fare_trips = self.count("SELECT COUNT(*) FROM trips WHERE route_id LIKE '%:extra-fare'")
        self.assertGreater(extra_fare_trips, 0)


class TobuBusSeedTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.info = json.loads((ROOT / 'app/src/main/assets/bootstrap/tobu-bus-info.json').read_text(encoding='utf-8'))
        raw = gzip.decompress((ROOT / 'app/src/main/assets/bootstrap/tobu-bus.bundle').read_bytes())
        self.assertEqual(self.info['size'], len(raw))
        self.assertEqual(self.info['sha256'], hashlib.sha256(raw).hexdigest())
        path = Path(self.temp.name) / 'tobu_bus_seed.db'
        path.write_bytes(raw)
        self.db = sqlite3.connect(path)

    def tearDown(self):
        self.db.close()
        self.temp.cleanup()

    def count(self, sql):
        return self.db.execute(sql).fetchone()[0]

    def test_bus_counts_and_validity(self):
        self.assertEqual('ok', self.db.execute('PRAGMA integrity_check').fetchone()[0])
        self.assertEqual(self.info['stations'], self.count('SELECT COUNT(*) FROM stations WHERE kind="bus"'))
        self.assertEqual(self.info['trips'], self.count('SELECT COUNT(*) FROM trips'))
        self.assertEqual(self.info['routes'], self.count("SELECT COUNT(*) FROM routes WHERE operator='東武バス'"))

        # All bus stops must have non-zero realistic coordinates in Kanto area (lat: 35.0~37.0, lon: 139.0~140.5)
        bad_coords = self.count("SELECT COUNT(*) FROM stations WHERE kind='bus' AND (lat < 35.0 OR lat > 37.0 OR lon < 139.0 OR lon > 140.5)")
        self.assertEqual(0, bad_coords, "All bus stops must have valid coordinates in Kanto")


if __name__ == '__main__':
    unittest.main()
