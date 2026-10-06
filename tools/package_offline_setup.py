#!/usr/bin/env python3
"""Package the Windows preparation tool and verified generated Keio bus seed."""
import argparse
import gzip
import hashlib
import json
from pathlib import Path
import zipfile

ROOT = Path(__file__).resolve().parents[1]

def package(output):
    assets = ROOT/'app/src/main/assets/bootstrap'
    bundle = (assets/'keio-bus.bundle').read_bytes()
    info = json.loads((assets/'keio-bus-info.json').read_text())
    raw = gzip.decompress(bundle)
    if len(raw) != info['size'] or hashlib.sha256(raw).hexdigest() != info['sha256']:
        raise ValueError('Generated Keio bus data does not match the project metadata')
    readme = '''# ビルド用データの準備（Windows）

1. このZIPを右クリック→「すべて展開」します。ZIPの中から直接実行しないでください。
2. 展開したフォルダーの prepare_offline_data.cmd をダブルクリックします。
3. 求められたらOfflineTransitMapのプロジェクトフォルダー（appとgradlew.batが入っている場所）を選びます。
4. 次に、共有していただいた元のtimetable.dbとPMTilesファイルを選びます。
5. Ready と表示されたらAndroid Studioで再度ビルドしてください。

生成済みの京王バスデータはこのZIPに入っています。Pythonの導入・変換作業は不要です。
DBと地図は、以前共有した元ファイルを使用してください。別バージョンを選ぶとハッシュ照合で停止します。
元ファイルは移動・削除しません。既存の同梱ファイルが異なる場合はLocalAppData/OfflineTransitMap/data-backupsに退避コピーを作ってから置き換えます。
同じプロジェクトでは初回だけの準備です。完成したAPKを導入する各端末にDBや地図を手作業で配置する必要はありません。

今回のエラーはGit対象外の大容量ファイルがビルドPCにないことが原因です。Gradle/Kotlinや京王の時刻表コードのコンパイルエラーではありません。
準備ツールのコマンドライン処理はPowerShell 7.4.6で実行検証済み。Windowsのファイル選択画面自体は未確認です。
'''
    output.parent.mkdir(parents=True, exist_ok=True)
    prefix = 'OfflineTransitMap-data-setup/'
    with zipfile.ZipFile(output, 'w', compression=zipfile.ZIP_DEFLATED) as archive:
        for name in ('prepare_offline_data.ps1','prepare_offline_data.cmd'):
            data = (ROOT/'tools'/name).read_bytes()
            if name.endswith('.cmd'): data = data.replace(b'\r\n',b'\n').replace(b'\n',b'\r\n')
            archive.writestr(prefix+name,data)
        archive.writestr(prefix+'keio-bus.bundle',bundle)
        archive.writestr(prefix+'README.txt',readme.encode('utf-8-sig'))
    print(f'Created {output} ({output.stat().st_size:,} bytes)')

if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('output',type=Path)
    package(parser.parse_args().output)
