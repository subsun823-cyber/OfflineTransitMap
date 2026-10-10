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
spec = importlib.util.spec_from_file_location('builder', ROOT / 'tools/build_seibu_data.py')
builder = importlib.util.module_from_spec(spec)
spec.loader.exec_module(builder)


class SeibuSeedTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.info = json.loads((ROOT / 'app/src/main/assets/bootstrap/seibu-info.json').read_text(encoding='utf-8'))
        raw = gzip.decompress((ROOT / 'app/src/main/assets/bootstrap/seibu.bundle').read_bytes())
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
        self.assertEqual(self.info['stations'], self.count("SELECT COUNT(*) FROM station_operators WHERE operator='西武鉄道'"))
        
        # 主要駅の存在確認
        ikebukuro = self.db.execute("SELECT name FROM stations WHERE station_id LIKE '%Seibu.Ikebukuro.Ikebukuro'").fetchone()
        self.assertIsNotNone(ikebukuro)
        self.assertEqual('池袋', ikebukuro[0])

        shinjuku = self.db.execute("SELECT name FROM stations WHERE station_id LIKE '%Seibu.Shinjuku.SeibuShinjuku'").fetchone()
        self.assertIsNotNone(shinjuku)
        self.assertEqual('西武新宿', shinjuku[0])

        tokorozawa = self.db.execute("SELECT name FROM stations WHERE name='所沢'").fetchall()
        self.assertGreaterEqual(len(tokorozawa), 1)

        honkawagoe = self.db.execute("SELECT name FROM stations WHERE station_id LIKE '%Seibu.Shinjuku.HonKawagoe'").fetchone()
        self.assertIsNotNone(honkawagoe)
        self.assertEqual('本川越', honkawagoe[0])

        chichibu = self.db.execute("SELECT name FROM stations WHERE station_id LIKE '%Seibu.SeibuChichibu.SeibuChichibu'").fetchone()
        self.assertIsNotNone(chichibu)
        self.assertEqual('西武秩父', chichibu[0])

    def test_platform_is_empty_for_rail_stations(self):
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

    def test_routes_and_special_express(self):
        self.assertEqual(12, self.count("SELECT COUNT(*) FROM routes WHERE route_id NOT LIKE '%:extra-fare'"))
        self.assertEqual(12, self.count("SELECT COUNT(*) FROM routes WHERE route_id LIKE '%:extra-fare'"))
        self.assertGreater(self.count("SELECT COUNT(*) FROM trips WHERE train_type LIKE '%特急%'"), 0)
        self.assertGreater(self.count("SELECT COUNT(*) FROM trips WHERE train_type LIKE '%拝島ライナー%'"), 0)
        self.assertGreater(self.count("SELECT COUNT(*) FROM trips WHERE train_type LIKE '%S-TRAIN%'"), 0)


if __name__ == '__main__':
    unittest.main()
