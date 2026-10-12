#!/usr/bin/env python3
"""
全国47都道府県の PMTiles 地図データを Protomaps リモートデータセットから抽出するスクリプト。

HTTP Range Request により、100GB超の全体ファイルをダウンロードすることなく、
指定した都道府県の範囲のみを高速に抽出（約40〜160MB）します。

使用例:
  # 大阪府を抽出
  python tools/extract_pmtiles.py --pref osaka

  # 複数都道府県を抽出
  python tools/extract_pmtiles.py --pref kyoto --pref hyogo

  # 特定地域の全県を抽出 (kanto, kansai, tohoku, chubu, chugoku, shikoku, kyushu, all)
  python tools/extract_pmtiles.py --region kansai

  # 抽出したPMTilesから道路データベース (roads_*.db) も自動生成
  python tools/extract_pmtiles.py --pref osaka --build-roads
"""

import argparse
import os
import platform
import subprocess
import sys
import urllib.request
import zipfile
import tarfile

DEFAULT_SOURCE_URL = "https://data.source.coop/protomaps/openstreetmap/tiles/v3.pmtiles"

# 全47都道府県の境界定義 (West, South, East, North)
PREFECTURES = {
    # --- 北海道・東北 ---
    "hokkaido": {"name": "北海道", "region": "hokkaido", "file": "hokkaido.pmtiles", "bbox": "139.33,41.35,145.82,45.56"},
    "aomori":   {"name": "青森県", "region": "tohoku",   "file": "aomori.pmtiles",   "bbox": "139.50,40.22,141.68,41.56"},
    "iwate":    {"name": "岩手県", "region": "tohoku",   "file": "iwate.pmtiles",    "bbox": "140.66,38.74,142.07,40.45"},
    "miyagi":   {"name": "宮城県", "region": "tohoku",   "file": "miyagi.pmtiles",   "bbox": "140.27,37.77,141.68,39.00"},
    "akita":    {"name": "秋田県", "region": "tohoku",   "file": "akita.pmtiles",    "bbox": "139.69,38.90,140.99,40.52"},
    "yamagata": {"name": "山形県", "region": "tohoku",   "file": "yamagata.pmtiles", "bbox": "139.54,37.75,140.64,39.15"},
    "fukushima":{"name": "福島県", "region": "tohoku",   "file": "fukushima.pmtiles","bbox": "139.17,36.78,141.04,37.98"},

    # --- 関東 ---
    "tokyo":    {"name": "東京都", "region": "kanto",    "file": "tokyo.pmtiles",    "bbox": "138.94,35.50,139.92,35.90"},
    "kanagawa": {"name": "神奈川県", "region": "kanto", "file": "kanagawa.pmtiles", "bbox": "138.91,35.12,139.80,35.68"},
    "saitama":  {"name": "埼玉県", "region": "kanto",    "file": "saitama.pmtiles",  "bbox": "138.71,35.75,139.90,36.29"},
    "chiba":    {"name": "千葉県", "region": "kanto",    "file": "chiba.pmtiles",    "bbox": "139.74,34.90,140.88,36.11"},
    "ibaraki":  {"name": "茨城県", "region": "kanto",    "file": "ibaraki.pmtiles",  "bbox": "139.68,35.74,140.85,36.95"},
    "tochigi":  {"name": "栃木県", "region": "kanto",    "file": "tochigi.pmtiles",  "bbox": "139.32,36.20,140.29,37.15"},
    "gunma":    {"name": "群馬県", "region": "kanto",    "file": "gunma.pmtiles",    "bbox": "138.39,36.08,139.66,37.06"},

    # --- 中部・甲信越・北陸・東海 ---
    "niigata":  {"name": "新潟県", "region": "chubu",    "file": "niigata.pmtiles",  "bbox": "137.64,36.74,139.90,38.56"},
    "toyama":   {"name": "富山県", "region": "chubu",    "file": "toyama.pmtiles",   "bbox": "136.78,36.27,137.76,36.98"},
    "ishikawa": {"name": "石川県", "region": "chubu",    "file": "ishikawa.pmtiles", "bbox": "136.24,36.07,137.37,37.52"},
    "fukui":    {"name": "福井県", "region": "chubu",    "file": "fukui.pmtiles",    "bbox": "135.45,35.35,136.83,36.30"},
    "yamanashi":{"name": "山梨県", "region": "chubu",    "file": "yamanashi.pmtiles","bbox": "138.18,35.17,139.14,35.98"},
    "nagano":   {"name": "長野県", "region": "chubu",    "file": "nagano.pmtiles",   "bbox": "137.31,35.19,138.75,37.04"},
    "gifu":     {"name": "岐阜県", "region": "chubu",    "file": "gifu.pmtiles",     "bbox": "136.27,35.14,137.65,36.47"},
    "shizuoka": {"name": "静岡県", "region": "chubu",    "file": "shizuoka.pmtiles", "bbox": "137.48,34.59,139.15,35.64"},
    "aichi":    {"name": "愛知県", "region": "chubu",    "file": "aichi.pmtiles",    "bbox": "136.67,34.58,137.84,35.42"},
    "mie":      {"name": "三重県", "region": "chubu",    "file": "mie.pmtiles",      "bbox": "135.85,33.72,136.99,35.26"},

    # --- 近畿（関西） ---
    "shiga":    {"name": "滋賀県", "region": "kansai",   "file": "shiga.pmtiles",    "bbox": "135.85,34.78,136.46,35.70"},
    "kyoto":    {"name": "京都府", "region": "kansai",   "file": "kyoto.pmtiles",    "bbox": "134.86,34.78,136.05,35.77"},
    "osaka":    {"name": "大阪府", "region": "kansai",   "file": "osaka.pmtiles",    "bbox": "135.09,34.28,135.75,35.05"},
    "hyogo":    {"name": "兵庫県", "region": "kansai",   "file": "hyogo.pmtiles",    "bbox": "134.25,34.15,135.47,35.68"},
    "nara":     {"name": "奈良県", "region": "kansai",   "file": "nara.pmtiles",     "bbox": "135.52,33.85,136.23,34.79"},
    "wakayama": {"name": "和歌山県", "region": "kansai", "file": "wakayama.pmtiles", "bbox": "134.99,33.43,135.99,34.37"},

    # --- 中国・四国 ---
    "tottori":  {"name": "鳥取県", "region": "chugoku",  "file": "tottori.pmtiles",  "bbox": "133.15,35.13,134.45,35.62"},
    "shimane":  {"name": "島根県", "region": "chugoku",  "file": "shimane.pmtiles",  "bbox": "131.66,34.30,133.39,36.35"},
    "okayama":  {"name": "岡山県", "region": "chugoku",  "file": "okayama.pmtiles",  "bbox": "133.34,34.30,134.42,35.36"},
    "hiroshima":{"name": "広島県", "region": "chugoku",  "file": "hiroshima.pmtiles","bbox": "132.03,34.03,133.51,35.10"},
    "yamaguchi":{"name": "山口県", "region": "chugoku",  "file": "yamaguchi.pmtiles","bbox": "130.75,33.71,132.25,34.62"},
    "tokushima":{"name": "徳島県", "region": "shikoku",  "file": "tokushima.pmtiles","bbox": "133.62,33.54,134.82,34.24"},
    "kagawa":   {"name": "香川県", "region": "shikoku",  "file": "kagawa.pmtiles",   "bbox": "133.45,34.01,134.44,34.57"},
    "ehime":    {"name": "愛媛県", "region": "shikoku",  "file": "ehime.pmtiles",    "bbox": "132.01,32.89,133.69,34.31"},
    "kochi":    {"name": "高知県", "region": "shikoku",  "file": "kochi.pmtiles",    "bbox": "132.48,32.70,134.31,33.88"},

    # --- 九州・沖縄 ---
    "fukuoka":  {"name": "福岡県", "region": "kyushu",   "file": "fukuoka.pmtiles",  "bbox": "129.98,33.00,131.19,34.24"},
    "saga":     {"name": "佐賀県", "region": "kyushu",   "file": "saga.pmtiles",     "bbox": "129.74,32.96,130.55,33.62"},
    "nagasaki": {"name": "長崎県", "region": "kyushu",   "file": "nagasaki.pmtiles", "bbox": "128.59,32.57,130.40,34.73"},
    "kumamoto": {"name": "熊本県", "region": "kyushu",   "file": "kumamoto.pmtiles", "bbox": "129.98,32.08,131.33,33.19"},
    "oita":     {"name": "大分県", "region": "kyushu",   "file": "oita.pmtiles",     "bbox": "130.84,32.72,132.09,33.74"},
    "miyazaki": {"name": "宮崎県", "region": "kyushu",   "file": "miyazaki.pmtiles", "bbox": "130.70,31.35,131.88,32.84"},
    "kagoshima":{"name": "鹿児島県", "region": "kyushu", "file": "kagoshima.pmtiles","bbox": "128.40,30.20,131.20,32.32"},
    "okinawa":  {"name": "沖縄県", "region": "okinawa",  "file": "okinawa.pmtiles",  "bbox": "127.60,26.05,128.35,26.88"},
}

PMTILES_CLI_VERSION = "1.22.1"


def get_pmtiles_cli_path() -> str:
    from shutil import which
    system_pmtiles = which("pmtiles") or which("pmtiles.exe")
    if system_pmtiles:
        return system_pmtiles

    tools_dir = os.path.dirname(os.path.abspath(__file__))
    bin_dir = os.path.join(tools_dir, "bin")
    os.makedirs(bin_dir, exist_ok=True)

    system = platform.system().lower()
    machine = platform.machine().lower()

    exe_name = "pmtiles.exe" if system == "windows" else "pmtiles"
    local_path = os.path.join(bin_dir, exe_name)
    if os.path.isfile(local_path):
        return local_path

    arch = "x86_64" if machine in ("amd64", "x86_64") else ("arm64" if "arm" in machine else "x86_64")
    os_name = "Windows" if system == "windows" else ("Darwin" if system == "darwin" else "Linux")
    ext = "zip" if system == "windows" else "tar.gz"

    download_url = f"https://github.com/protomaps/go-pmtiles/releases/download/v{PMTILES_CLI_VERSION}/go-pmtiles_{PMTILES_CLI_VERSION}_{os_name}_{arch}.{ext}"
    archive_path = os.path.join(bin_dir, f"pmtiles.{ext}")

    print(f"pmtiles CLI をダウンロード中: {download_url}")
    urllib.request.urlretrieve(download_url, archive_path)

    if ext == "zip":
        with zipfile.ZipFile(archive_path, 'r') as zip_ref:
            zip_ref.extractall(bin_dir)
    else:
        with tarfile.open(archive_path, 'r:gz') as tar_ref:
            tar_ref.extractall(bin_dir)

    if os.path.isfile(archive_path):
        os.remove(archive_path)

    if os.path.isfile(local_path):
        if system != "windows":
            os.chmod(local_path, 0o755)
        return local_path

    raise RuntimeError(f"pmtiles バイナリの配置に失敗しました: {local_path}")


def extract_prefecture(pmtiles_bin: str, pref_id: str, source_url: str, out_dir: str, build_roads: bool = False):
    info = PREFECTURES.get(pref_id)
    if not info:
        print(f"不明な都府県ID: {pref_id}", file=sys.stderr)
        return

    os.makedirs(out_dir, exist_ok=True)
    out_file = os.path.join(out_dir, info["file"])
    print(f"[{info['name']}] PMTiles抽出を開始します: {out_file} (BBox: {info['bbox']})")

    cmd = [
        pmtiles_bin,
        "extract",
        source_url,
        out_file,
        f"--bbox={info['bbox']}",
    ]
    subprocess.run(cmd, check=True)
    size_mb = os.path.getsize(out_file) / 1024 / 1024
    print(f"[{info['name']}] PMTiles抽出完了: {size_mb:.1f} MB -> {out_file}")

    if build_roads:
        road_out_file = os.path.join(out_dir, f"roads_{pref_id}.db")
        print(f"[{info['name']}] 道路データ (roads_{pref_id}.db) を生成中...")
        road_cmd = [sys.executable, os.path.join(os.path.dirname(__file__), "build_production_roads.py"), out_file, road_out_file]
        subprocess.run(road_cmd, check=True)
        r_size_mb = os.path.getsize(road_out_file) / 1024 / 1024
        print(f"[{info['name']}] 道路データ生成完了: {r_size_mb:.1f} MB -> {road_out_file}")


def main():
    parser = argparse.ArgumentParser(description="全国47都道府県の PMTiles 地図抽出ツール")
    parser.add_argument("--pref", action="append", help="抽出する都府県ID (複数指定可、例: --pref osaka --pref kyoto)")
    parser.add_argument("--region", choices=["hokkaido", "tohoku", "kanto", "chubu", "kansai", "chugoku", "shikoku", "kyushu", "all"], help="指定した地域の全都府県を抽出")
    parser.add_argument("--source", default=DEFAULT_SOURCE_URL, help="Protomaps リモートデータセットURL")
    parser.add_argument("--out", default=".", help="出力先ディレクトリ")
    parser.add_argument("--build-roads", action="store_true", help="PMTiles抽出後に道路DB (roads_*.db) も自動生成")

    args = parser.parse_args()

    targets = []
    if args.pref:
        targets.extend(args.pref)
    if args.region:
        if args.region == "all":
            targets = list(PREFECTURES.keys())
        else:
            targets.extend([k for k, v in PREFECTURES.items() if v["region"] == args.region])

    if not targets:
        parser.print_help()
        print("\n利用可能な都府県ID一覧:")
        for pid, pdata in PREFECTURES.items():
            print(f"  {pid:12s} : {pdata['name']} (地域: {pdata['region']})")
        sys.exit(1)

    targets = list(dict.fromkeys(targets)) # 重複除去
    pmtiles_bin = get_pmtiles_cli_path()
    print(f"使用する pmtiles CLI: {pmtiles_bin}")
    print(f"対象都府県数: {len(targets)} ({', '.join(targets)})")

    for tid in targets:
        extract_prefecture(pmtiles_bin, tid, args.source, args.out, build_roads=args.build_roads)


if __name__ == "__main__":
    main()
