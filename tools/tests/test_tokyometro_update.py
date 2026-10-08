import gzip
import hashlib
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch, MagicMock

ROOT = Path(__file__).resolve().parents[2]
import sys
sys.path.insert(0, str(ROOT / 'tools'))
import fetch_and_build_transit_data as fb


class TokyoMetroUpdateCheckTest(unittest.TestCase):
    def setUp(self):
        self.temp_dir = tempfile.TemporaryDirectory()
        self.output_dir = Path(self.temp_dir.name)
        self.bundle_path = ROOT / 'app/src/main/assets/bootstrap/tokyometro.bundle'
        self.info_path = ROOT / 'app/src/main/assets/bootstrap/tokyometro-info.json'

    def tearDown(self):
        self.temp_dir.cleanup()

    def test_fallback_to_bundle_when_no_token(self):
        seed_file, version = fb.check_and_fetch_odpt_tokyometro(
            token="",
            output_dir=self.output_dir,
            bundle_path=self.bundle_path,
            info_path=self.info_path
        )
        self.assertIsNotNone(seed_file)
        self.assertTrue(seed_file.exists())
        self.assertGreater(seed_file.stat().st_size, 0)
        expected_info = json.loads(self.info_path.read_text(encoding='utf-8'))
        self.assertEqual(expected_info['sha256'], version)

    @patch('urllib.request.urlopen')
    def test_standard_token_checks_odpt_api_and_detects_uptodate(self, mock_urlopen):
        # Mock ODPT Railway API returning up-to-date timestamp
        mock_response = MagicMock()
        mock_response.status = 200
        mock_response.read.return_value = json.dumps([
            {"dc:date": "2024-06-27T08:00:00+09:00", "odpt:operator": "odpt.Operator:TokyoMetro"}
        ]).encode('utf-8')
        mock_urlopen.return_value.__enter__.return_value = mock_response

        # Temporary info file with matching date
        fake_info = self.output_dir / "temp-info.json"
        fake_info.write_text(json.dumps({
            "sourceTimetableDate": "2024-06-27T08:00:00+09:00",
            "sha256": "dummy_sha"
        }), encoding='utf-8')

        test_token = "standard_test_token_12345"
        seed_file, version = fb.check_and_fetch_odpt_tokyometro(
            token=test_token,
            output_dir=self.output_dir,
            bundle_path=self.bundle_path,
            info_path=fake_info
        )

        # Verify that urlopen was called with standard token
        called_urls = [call[0][0].full_url for call in mock_urlopen.call_args_list]
        self.assertTrue(any("api-public.odpt.org" in u or "api.odpt.org" in u for u in called_urls))
        self.assertTrue(any(test_token in u for u in called_urls))
        self.assertTrue(any("TokyoMetro" in u for u in called_urls))

        # Since data was up-to-date, it should use the bundle
        self.assertIsNotNone(seed_file)
        self.assertTrue(seed_file.exists())

    @patch('urllib.request.urlopen')
    def test_standard_token_network_error_falls_back_to_bundle(self, mock_urlopen):
        mock_urlopen.side_effect = Exception("Simulated network timeout")

        seed_file, version = fb.check_and_fetch_odpt_tokyometro(
            token="valid_standard_token",
            output_dir=self.output_dir,
            bundle_path=self.bundle_path,
            info_path=self.info_path
        )
        self.assertIsNotNone(seed_file)
        self.assertTrue(seed_file.exists())
        expected_info = json.loads(self.info_path.read_text(encoding='utf-8'))
        self.assertEqual(expected_info['sha256'], version)


if __name__ == '__main__':
    unittest.main()
