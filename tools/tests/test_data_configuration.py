import argparse
import gzip
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest

ROOT=Path(__file__).resolve().parents[2]
spec=importlib.util.spec_from_file_location('configure',ROOT/'tools/configure_data_files.py')
config=importlib.util.module_from_spec(spec);spec.loader.exec_module(config)

class DistributionConfigTest(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory();self.root=Path(self.temp.name)
        self.previous=config.ROOT;config.ROOT=self.root
        (self.root/'app/src/main/assets/bootstrap').mkdir(parents=True)
        self.db=self.root/'original.db'
        self.db.write_bytes(gzip.decompress((ROOT/'app/src/main/assets/bootstrap/keio.bundle').read_bytes()))
    def tearDown(self):config.ROOT=self.previous;self.temp.cleanup()
    def configure(self,**kwargs):
        args=dict(timetable=self.db,timetable_url=None,map=None,map_url=None);args.update(kwargs)
        config.configure(argparse.Namespace(**args))
        return json.loads((self.root/'app/src/main/assets/bootstrap/data-files.json').read_text())['files']
    def test_bundle_copies_validated_file_and_records_size_hash(self):
        entries=self.configure()
        self.assertEqual('bootstrap/timetable.db',entries[0]['asset'])
        self.assertEqual(self.db.stat().st_size,entries[0]['size'])
        self.assertEqual(self.db.read_bytes(),(self.root/'app/src/main/assets/bootstrap/timetable.db').read_bytes())
    def test_download_profile_does_not_bundle_large_files(self):
        url='https://example.invalid/timetable.db'
        entry=self.configure(timetable_url=url)[0]
        self.assertEqual(url,entry['url']);self.assertNotIn('asset',entry)
        self.assertFalse((self.root/'app/src/main/assets/bootstrap/timetable.db').exists())
    def test_invalid_map_and_insecure_url_rejected(self):
        bad=self.root/'bad.pmtiles';bad.write_bytes(b'not a map')
        with self.assertRaises(ValueError):self.configure(timetable=None,map=bad)
        with self.assertRaises(ValueError):self.configure(timetable_url='http://example.invalid/file')
    def test_uncheckpointed_database_not_distributed(self):
        Path(str(self.db)+'-wal').write_bytes(b'pending changes')
        with self.assertRaises(ValueError):self.configure()

if __name__=='__main__':unittest.main()
