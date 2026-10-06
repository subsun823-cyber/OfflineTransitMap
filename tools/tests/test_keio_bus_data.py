import datetime as dt
import gzip
import hashlib
import json
from pathlib import Path
import sqlite3
import sys
import tempfile
import unittest

sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
from build_keio_bus_data import PREFIX, fare_rows, seconds

ASSETS = Path(__file__).resolve().parents[2]/'app/src/main/assets/bootstrap'

class KeioBusDataTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.directory=tempfile.TemporaryDirectory()
        cls.info=json.loads((ASSETS/'keio-bus-info.json').read_text())
        raw=gzip.decompress((ASSETS/'keio-bus.bundle').read_bytes())
        assert len(raw)==cls.info['size'] and hashlib.sha256(raw).hexdigest()==cls.info['sha256']
        file=Path(cls.directory.name)/'seed.db';file.write_bytes(raw)
        cls.db=sqlite3.connect(file)

    @classmethod
    def tearDownClass(cls):
        cls.db.close();cls.directory.cleanup()

    def test_actual_seed_integrity_coverage_and_references(self):
        self.assertEqual(self.db.execute('PRAGMA integrity_check').fetchone()[0],'ok')
        for table,count in [('stops',2927),('routes',254),('trips',29550),('stop_times',447000),('calendar_dates',171)]:
            self.assertEqual(self.db.execute(f'SELECT COUNT(*) FROM {table}').fetchone()[0],count)
        for table,col,parent,key in [('trips','route_id','routes','route_id'),('stop_times','trip','trips','trip_no'),('stop_times','stop_id','stops','stop_id'),('stops','station_id','stations','station_id')]:
            self.assertEqual(self.db.execute(f'SELECT COUNT(*) FROM {table} a LEFT JOIN {parent} b ON a.{col}=b.{key} WHERE b.{key} IS NULL').fetchone()[0],0)

    def test_groups_and_kana_and_bus_kind(self):
        self.assertEqual(self.db.execute('SELECT COUNT(DISTINCT grp) FROM stations').fetchone()[0],1529)
        self.assertEqual(self.db.execute("SELECT DISTINCT kind FROM stations").fetchall(),[('bus',)])
        self.assertTrue(self.db.execute("SELECT 1 FROM stations WHERE name='高尾駅南口' AND kana!=''").fetchone())
        self.assertEqual(self.db.execute("SELECT COUNT(DISTINCT grp) FROM stations WHERE name='高尾駅南口'").fetchone()[0],1)

    def services(self,date):
        col=['mon','tue','wed','thu','fri','sat','sun'][date.weekday()];day=int(date.strftime('%Y%m%d'))
        result={x[0] for x in self.db.execute(f'SELECT service_id FROM calendar WHERE {col}=1 AND start_date<=? AND end_date>=?',(day,day))}
        for service,exception in self.db.execute('SELECT service_id,exception_type FROM calendar_dates WHERE date=?',(day,)):
            if exception==1:result.add(service)
            else:result.discard(service)
        return result

    def test_weekday_saturday_holiday_and_validity(self):
        for date,label in [(dt.date(2026,10,6),'平日'),(dt.date(2026,10,10),'土曜'),(dt.date(2026,10,12),'休日')]:
            active=self.services(date);self.assertTrue(active)
            self.assertTrue(all(label in service for service in active),active)
        self.assertFalse(self.services(dt.date(2026,9,30)))
        self.assertFalse(self.services(dt.date(2027,1,1)))

    def test_stop_times_permissions_headsign_and_after_midnight(self):
        self.assertEqual(seconds('24:13:00'),87180)
        self.assertEqual(seconds('00:13:00'),780) # GTFS already encodes service-day hours.
        with self.assertRaises(ValueError):seconds('24:60:00')
        self.assertEqual(self.db.execute('SELECT MAX(dep_sec) FROM stop_times').fetchone()[0],87180)
        self.assertEqual(self.db.execute('SELECT COUNT(*) FROM stop_times WHERE can_board=0').fetchone()[0],31034)
        self.assertEqual(self.db.execute('SELECT COUNT(*) FROM stop_times WHERE can_alight=0').fetchone()[0],29550)
        self.assertEqual(self.db.execute("SELECT COUNT(*) FROM trips WHERE headsign IS NULL OR headsign='' OR train_type!=''").fetchone()[0],0)

    def test_complex_routes_excluded_and_uniform_fares_expanded(self):
        for route in self.info['excludedFareRoutes']:
            self.assertFalse(self.db.execute('SELECT 1 FROM fares WHERE route_id=?',(PREFIX+route,)).fetchone())
        self.assertEqual(self.db.execute('SELECT DISTINCT price FROM fares WHERE route_id=?',(PREFIX+'16',)).fetchall(),[(200,)])
        self.assertGreater(self.db.execute('SELECT COUNT(*) FROM fares WHERE route_id=?',(PREFIX+'16',)).fetchone()[0],1)

    def test_conflicting_and_path_fares_never_become_a_guessed_price(self):
        attrs={k:{'price':v,'currency_type':'JPY','transfers':'0'} for k,v in [('a','100'),('b','200')]}
        def rule(fare,route='r',contains=''):
            return dict(fare_id=fare,route_id=route,origin_id='x',destination_id='y',contains_id=contains)
        rows,excluded,ambiguous=fare_rows([rule('a'),rule('b'),rule('a','s','via')],attrs,{'r':{'x','y'},'s':{'x','y'}})
        self.assertEqual(rows,[]);self.assertEqual(excluded,['s']);self.assertEqual(ambiguous,1)

if __name__=='__main__':unittest.main()
