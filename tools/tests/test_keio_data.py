import datetime as dt
import gzip
import hashlib
import importlib.util
import json
from pathlib import Path
import sqlite3
import tempfile
import unittest

ROOT=Path(__file__).resolve().parents[2]
spec=importlib.util.spec_from_file_location('builder',ROOT/'tools/build_keio_data.py')
builder=importlib.util.module_from_spec(spec);spec.loader.exec_module(builder)

class KeioSeedTest(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory()
        self.info=json.loads((ROOT/'app/src/main/assets/bootstrap/keio-info.json').read_text())
        raw=gzip.decompress((ROOT/'app/src/main/assets/bootstrap/keio.bundle').read_bytes())
        self.assertEqual(self.info['size'],len(raw))
        self.assertEqual(self.info['sha256'],hashlib.sha256(raw).hexdigest())
        path=Path(self.temp.name)/'seed.db';path.write_bytes(raw)
        self.db=sqlite3.connect(path)
    def tearDown(self):
        self.db.close();self.temp.cleanup()
    def count(self,sql):return self.db.execute(sql).fetchone()[0]
    def test_counts_and_no_orphans(self):
        self.assertEqual('ok',self.db.execute('PRAGMA integrity_check').fetchone()[0])
        self.assertEqual(self.info['stations'],self.count('SELECT COUNT(*) FROM stations'))
        self.assertEqual(self.info['importedTrains'],self.count('SELECT COUNT(*) FROM trips'))
        for table,key in [('trips','trip_no'),('stations','station_id'),('stops','stop_id')]:
            foreign='trip' if table=='trips' else key
            self.assertEqual(0,self.count(f'SELECT COUNT(*) FROM stop_times s LEFT JOIN {table} t ON s.{foreign}=t.{key} WHERE t.{key} IS NULL'))
        self.assertEqual(0,self.count('SELECT COUNT(*) FROM (SELECT trip,seq,COUNT(*) n FROM stop_times GROUP BY trip,seq HAVING n>1)'))
    def test_all_stations_have_operator_even_without_timetables(self):
        self.assertEqual(self.info['stations'],self.count("SELECT COUNT(*) FROM station_operators WHERE operator='京王電鉄'"))
        self.assertGreater(self.count("SELECT COUNT(*) FROM stations s WHERE NOT EXISTS (SELECT 1 FROM stop_times t WHERE t.station_id=s.station_id)"),0)
        self.assertEqual('高尾',self.db.execute("SELECT name FROM stations WHERE station_id LIKE '%Keio.Takao.Takao'").fetchone()[0])
    def active(self,date):
        col=['mon','tue','wed','thu','fri','sat','sun'][date.weekday()];ymd=int(date.strftime('%Y%m%d'))
        active={row[0] for row in self.db.execute(f'SELECT service_id FROM calendar WHERE {col}=1 AND start_date<=? AND end_date>=?',(ymd,ymd))}
        for service,kind in self.db.execute('SELECT service_id,exception_type FROM calendar_dates WHERE date=?',(ymd,)):
            if kind==1:active.add(service)
            else:active.discard(service)
        return active
    def test_weekdays_weekends_and_japanese_holidays(self):
        self.assertEqual({builder.PREFIX+'odpt.Calendar:Weekday'},self.active(dt.date(2026,10,6)))
        expected={builder.PREFIX+'odpt.Calendar:SaturdayHoliday'}
        self.assertEqual(expected,self.active(dt.date(2026,10,10)))
        self.assertEqual(expected,self.active(dt.date(2026,10,12)))
        self.assertEqual(set(),self.active(dt.date(2026,12,31)))
        self.assertEqual(set(),self.active(dt.date(2027,1,1)))
        self.assertEqual(set(),self.active(dt.date(2028,10,6)))
        self.assertEqual(0,self.count("SELECT COUNT(*) FROM calendar WHERE service_id LIKE '%RaceDay%'"))
    def test_times_and_terminal_permissions(self):
        self.assertEqual(0,self.count('SELECT COUNT(*) FROM stop_times WHERE arr_sec>dep_sec'))
        self.assertEqual(0,self.count('SELECT COUNT(*) FROM stop_times a JOIN stop_times b ON a.trip=b.trip AND a.seq+1=b.seq WHERE a.dep_sec>b.arr_sec'))
        self.assertGreater(self.count('SELECT COUNT(*) FROM stop_times WHERE can_board=0 AND can_alight=1'),0)
        self.assertEqual(24*3600+10*60,builder.seconds('00:10',23*3600))
        self.assertEqual(25*3600+10*60,builder.seconds('25:10'))
    def test_missing_fares_are_not_invented_and_liner_has_no_total_price(self):
        self.assertEqual(self.info['receivedFares'],self.count('SELECT COUNT(*) FROM (SELECT DISTINCT from_zone,to_zone FROM fares)'))
        self.assertEqual(0,self.count("SELECT COUNT(*) FROM fares WHERE route_id LIKE '%extra-fare'"))
        self.assertGreater(self.count("SELECT COUNT(*) FROM trips WHERE train_type='京王ライナー（別途料金）'"),0)
    def test_converter_rejects_invalid_times(self):
        with self.assertRaises(AssertionError):builder.seconds('24:99')
        with self.assertRaises(AssertionError):builder.seconds('48:00')

if __name__=='__main__':unittest.main()
