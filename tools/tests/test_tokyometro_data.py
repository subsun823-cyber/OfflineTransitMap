import datetime as dt
import gzip
import hashlib
import importlib.util
import json
from pathlib import Path
import sqlite3
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location('builder', ROOT / 'tools/build_tokyometro_data.py')
builder = importlib.util.module_from_spec(spec)
spec.loader.exec_module(builder)


class TokyoMetroSeedTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.info = json.loads((ROOT / 'app/src/main/assets/bootstrap/tokyometro-info.json').read_text(encoding='utf-8'))
        raw = gzip.decompress((ROOT / 'app/src/main/assets/bootstrap/tokyometro.bundle').read_bytes())
        self.assertEqual(self.info['size'], len(raw))
        self.assertEqual(self.info['sha256'], hashlib.sha256(raw).hexdigest())
        path = Path(self.temp.name) / 'seed.db'
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
        self.assertEqual(self.info['stations'], self.count("SELECT COUNT(*) FROM station_operators WHERE operator='東京メトロ'"))
        major_stations = [
            ('TokyoMetro.Marunouchi.Shinjuku', '新宿'),
            ('TokyoMetro.Ginza.Ginza', '銀座'),
            ('TokyoMetro.Chiyoda.Otemachi', '大手町'),
            ('TokyoMetro.Hibiya.Roppongi', '六本木'),
            ('TokyoMetro.Ginza.Asakusa', '浅草'),
            ('TokyoMetro.Ginza.Shibuya', '渋谷'),
            ('TokyoMetro.Marunouchi.Ikebukuro', '池袋'),
            ('TokyoMetro.Chiyoda.Kasumigaseki', '霞ケ関')  # or 霞ヶ関
        ]
        for st_suffix, name in major_stations:
            row = self.db.execute(f"SELECT name FROM stations WHERE station_id LIKE '%{st_suffix}'").fetchone()
            self.assertIsNotNone(row, f"Station {st_suffix} should exist")

    def test_platform_is_empty_for_rail_stations(self):
        # Platform must not be populated with stationCode (e.g. M08, G01)
        self.assertEqual(0, self.count("SELECT COUNT(*) FROM stops WHERE platform IS NOT NULL AND platform != ''"))

    def active(self, date):
        col = ['mon', 'tue', 'wed', 'thu', 'fri', 'sat', 'sun'][date.weekday()]
        ymd = int(date.strftime('%Y%m%d'))
        active = {row[0] for row in self.db.execute(
            f'SELECT service_id FROM calendar WHERE {col}=1 AND start_date<=? AND end_date>=?', (ymd, ymd))}
        for service, kind in self.db.execute('SELECT service_id,exception_type FROM calendar_dates WHERE date=?', (ymd,)):
            if kind == 1:
                active.add(service)
            else:
                active.discard(service)
        return active

    def test_weekdays_weekends_and_japanese_holidays(self):
        self.assertEqual({builder.PREFIX + 'odpt.Calendar:Weekday'}, self.active(dt.date(2026, 10, 6)))
        expected = {builder.PREFIX + 'odpt.Calendar:SaturdayHoliday'}
        self.assertEqual(expected, self.active(dt.date(2026, 10, 10)))
        self.assertEqual(expected, self.active(dt.date(2026, 10, 12)))
        self.assertEqual(set(), self.active(dt.date(2026, 12, 31)))
        self.assertEqual(set(), self.active(dt.date(2027, 1, 1)))
        self.assertEqual(set(), self.active(dt.date(2028, 10, 6)))

    def test_times_and_terminal_permissions(self):
        self.assertEqual(0, self.count('SELECT COUNT(*) FROM stop_times WHERE arr_sec>dep_sec'))
        self.assertEqual(0, self.count('SELECT COUNT(*) FROM stop_times a JOIN stop_times b ON a.trip=b.trip AND a.seq+1=b.seq WHERE a.dep_sec>b.arr_sec'))
        self.assertGreater(self.count('SELECT COUNT(*) FROM stop_times WHERE can_board=0 AND can_alight=1'), 0)

    def test_routes_and_fares(self):
        self.assertEqual(10, self.count("SELECT COUNT(*) FROM routes WHERE route_id NOT LIKE '%:extra-fare'"))
        self.assertGreater(self.count("SELECT COUNT(*) FROM fares"), 0)
        min_fare = self.count("SELECT MIN(price) FROM fares")
        max_fare = self.count("SELECT MAX(price) FROM fares")
        self.assertGreaterEqual(min_fare, 0)
        self.assertLessEqual(max_fare, 330)


if __name__ == '__main__':
    unittest.main()
