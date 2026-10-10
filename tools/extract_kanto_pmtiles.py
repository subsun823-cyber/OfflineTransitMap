#!/usr/bin/env python3
"""
関東各都県の PMTiles 地図データを Protomaps リモートデータセットから抽出するスクリプト。

巨大な全体ファイル（100GB超）をダウンロードすることなく、
HTTP Range Request により指定した都県の範囲（約80〜140MB）のみを高速に抽出します。

使用例:
  # 神奈川県を抽出
  python tools/extract_kanto_pmtiles.py --pref kanagawa

  # 関東全都県を一括抽出
  python tools/extract_kanto_pmtiles.py --all

  # 任意の出力ディレクトリを指定
  python tools/extract_kanto_pmtiles.py --pref saitama --out dist/maps/
"""

import argparse
import os
import platform
import subprocess
import sys
import urllib.request
import zipfile
import tarfile

# Source Cooperative がホストする公式 Protomaps OSM タイル (v3)
DEFAULT_SOURCE_URL = "https://data.source.coop/protomaps/openstreetmap/tiles/v3.pmtiles"

# 関東各都県の境界定義 (West, South, East, North)
KANTO_PREFECTURES = {
    "tokyo": {
        "name": "東京都",
        "file": "tokyo.pmtiles",
        "bbox": "138.94,35.50,139.92,35.90",
    },
    "kanagawa": {
        "name": "神奈川県",
        "file": "kanagawa.pmtiles",
        "bbox": "138.91,35.12,139.80,35.68",
    },
    "saitama": {
        "name": "埼玉県",
        "file": "saitama.pmtiles",
        "bbox": "138.71,35.75,139.90,36.29",
    },
    "chiba": {
        "name": "千葉県",
        "file": "chiba.pmtiles",
        "bbox": "139.74,34.90,140.88,36.11",
    },
    "ibaraki": {
        "name": "茨城県",
        "file": "ibaraki.pmtiles",
        "bbox": "139.68,35.74,140.85,36.95",
    },
    "tochigi": {
        "name": "栃木県",
        "file": "tochigi.pmtiles",
        "bbox": "139.32,36.20,140.29,37.15",
    },
    "gunma": {
        "name": "群馬県",
        "file": "gunma.pmtiles",
        "bbox": "138.39,36.08,139.66,37.06",
    },
}

PMTILES_CLI_VERSION = "1.22.1"


def get_pmtiles_cli_path() -> str:
    """ローカルに pmtiles CLI が存在すればそのパスを返し、無ければ自動取得する。"""
    # 既存の PATH 上を確認
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

    # OS・アーキテクチャに応じたダウンロードURL
    # https://github.com/protomaps/go-pmtiles/releases
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


def extract_prefecture(pmtiles_bin: str, pref_id: str, source_url: str, out_dir: str):
    info = KANTO_PREFECTURES.get(pref_id)
    if not info:
        print(f"不明な都県ID: {pref_id}", file=sys.stderr)
        return

    out_file = os.path.join(out_dir, info["file"])
    print(f"[{info['name']}] 抽出を開始します: {out_file} (BBox: {info['bbox']})")

    cmd = [
        pmtiles_bin,
        "extract",
        source_url,
        out_file,
        f"--bbox={info['bbox']}",
    ]
    subprocess.run(cmd, check=True)
    size_mb = os.path.getsize(out_file) / 1024 / 1024
    print(f"[{info['name']}] 抽出完了: {size_mb:.1f} MB -> {out_file}")


def main():
    parser = argparse.ArgumentParser(description="関東各都県の PMTiles 地図抽出ツール")
    parser.add_argument("--pref", choices=list(KANTO_PREFECTURES.keys()), help="抽出する都県ID (例: kanagawa)")
    parser.add_argument("--all", action="store_true", help="関東1都6県を全て抽出")
    parser.add_argument("--source", default=DEFAULT_SOURCE_URL, help="Protomaps リモートデータセットURL")
    parser.add_argument("--out", default=".", help="出力先ディレクトリ (デフォルト: カレントディレクトリ)")

    args = parser.parse_args()
    if not args.pref and not args.all:
        parser.print_help()
        print("\n利用可能な都県ID:")
        for pid, pdata in KANTO_PREFECTURES.items():
            print(f"  {pid:10s} : {pdata['name']} ({pdata['file']})")
        sys.exit(1)

    os.makedirs(args.out, exist_ok=True)
    pmtiles_bin = get_pmtiles_cli_path()
    print(f"使用する pmtiles CLI: {pmtiles_bin}")

    targets = list(KANTO_PREFECTURES.keys()) if args.all else [args.pref]
    for tid in targets:
        extract_prefecture(pmtiles_bin, tid, args.source, args.out)


if __name__ == "__main__":
    main()
