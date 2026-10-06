# 初回データ準備と複数端末への配布

## 現在の同梱内容と不足ファイル

京王の駅・通常日時刻表・収録済み運賃を `assets/bootstrap/keio.bundle` に同梱しています（約300KB、展開後約3.1MiB）。初回起動で `getExternalFilesDir("timetable")/timetable.db` を用意します。既存のスキーマv5のJR・バスDBがある場合は、そのコピーへ京王を追加して検証後に差し替えます。

**JR・バス入りの本体DBと `tokyo.pmtiles` は未受領です。現在の配布設定は空です。** 現在のAPKだけでは京王以外の時刻表と背景地図は揃いません。地図がない場合はオフラインで駅・経路のみを描画し、初回案内と設定画面に不足を表示します。

配布担当者が以下を一度設定してAPKを作れば、各端末へ手動でファイルを配置する必要がなくなります。Androidへのインストール後、**初回起動時**に展開・取得します。

## A. HTTPSからダウンロード（大容量データ向け）

1. アプリで使用している `timetable.db` と `tokyo.pmtiles` を用意します。DBを使用するアプリを終了し、WALに未反映の書き込みがない状態にしてください。
2. ファイル本体を配布するHTTPS URLを用意します。共有サービスの閲覧ページではなく、ファイル本体を取得できるURLが必要です。認証情報をURLやGitへ保存しないでください。
3. 下記の例のパス・URLを実際のものへ置き換えて実行します。

```sh
python3 tools/configure_data_files.py \
  --timetable /path/to/timetable.db --timetable-url https://example.invalid/data/timetable.db \
  --map /path/to/tokyo.pmtiles --map-url https://example.invalid/data/tokyo.pmtiles
```

このコマンドは実ファイルを検査し、サイズとSHA-256を計算して `app/src/main/assets/bootstrap/data-files.json` を生成します。入力ファイルをGitへコピーしません。上記のURLは説明用で、動作する配布先ではありません。

4. Android StudioでAPKをビルドし、同じAPKを各端末へ導入します。初回取得には通信と空き容量が必要です。その後はオフラインで使えます。

## B. APKに同梱（初回から通信不要）

```sh
python3 tools/configure_data_files.py \
  --timetable /path/to/timetable.db \
  --map /path/to/tokyo.pmtiles
```

URLを省略したファイルは `assets/bootstrap/` へコピーされ、APKに含まれます。大容量ファイルは `.gitignore` 対象です。ビルドを行う環境には、別途その実ファイルを用意してください。地図の大きさに応じてAPKも大きくなり、展開時はAPKと展開先の両方に保存容量を使います。

片方だけURLを指定すれば同梱とダウンロードを混在できます。コマンドは配布設定全体を生成するため、両方を配布する場合は1回の実行で両方指定します。

## 端末側の動作・保護

- ダウンロードと同梱ファイルのコピーは一時ファイルへ行い、サイズ・SHA-256を照合します。DBはSQLite検査と必要列、地図はPMTiles v3ヘッダーを確認します。
- 京王追加は既存DBのコピーに対してトランザクション内で行います。JR・バスの行を維持し、京王の内部便番号をずらして衝突を防ぎます。完成後に同一ディレクトリ内で原子的に差し替えます。
- 取得・検証が失敗した場合は完成済みファイルを維持します。「再試行」は最初から再取得します。通信できない場合は「同梱・保存済みデータで開始」を選べます。破損したDBをそのまま開くことはしません。
- 初回配布方式です。通常の既存DB・地図を毎回上書きしません。アプリが生成した京王のみのDBは、後から設定した本体DBへ移行できます。一般の配布データの世代管理・定期自動更新・ダウンロード再開は未実装です。
- アプリ更新で同梱京王データのハッシュが変われば、次回起動時に京王部分を更新します。既存DBのコピーが必要なため、その分の空き容量も必要です。
- ナビ・時刻表DB・地図を開く前に準備を完了します。実行中のナビでDBや地図を差し替えません。

## 京王データの収録範囲

ユーザー提供のODPT形式JSONを使用しています。

- 路線7、駅76レコード（同名・近接駅の統合後69駅）。全駅に京王の会社情報を付けるので、便が未収録の高尾などもJRとの共有駅表示に使えます。
- 受領時刻表1,000件のうち通常日834件。井の頭線・動物園線・京王線の一部のみです。高尾線・相模原線・競馬場線・京王新線の便は未収録です。1,000件という件数や欠落から、全件取得できていない可能性があります。
- 競馬開催日用166件は開催日がないため無効化。未提供の直通便19件へのリンクは接続を補完しません。異なる路線に分割された直通便の連結は未対応です。
- 運賃は受領した1,000組の大人普通運賃。逆方向や欠落区間を推定しません。京王ライナーは別料金を含むため、検索結果の総額を普通運賃だけで確定しません。
- 時刻表の更新表記は2026-07-03、発行表記は2025-03-15/17、適用終了日の記載なし。現在有効な完全版と断定できないため、初回案内・設定・京王駅の出発表示に部分データの注意を表示します。
- 祝日は[内閣府の公表CSV](https://www8.cao.go.jp/chosei/shukujitsu/syukujitsu.csv)を使用。カレンダーの収録範囲は2027年末までで、時刻表の有効期限を意味しません。年末年始の特別ダイヤがない12/30〜1/3は、誤案内を避けるため京王便を検索対象外にしています。
- `station_operators` と `app_data` の補助テーブルを追加します。既存の時刻表・乗換検索の主要テーブル形式はv5のままです。

## 同梱京王データの再生成

元のJSONと祝日CSVを配布担当者の作業フォルダに置いて実行します。元ファイルのハッシュ・件数は `keio-info.json` に記録しています。巨大な原データをGitへ追加する必要はありません。

```sh
python3 tools/build_keio_data.py \
  --railways odptRailway.json --stations odptStation.json \
  --trains odptTrainTimetable-keio.json --fares odptRailwayFare.json \
  --holidays syukujitsu.csv --output app/src/main/assets/bootstrap
python3 -m unittest discover -s tools/tests -v
```

全件版を受領した場合は、通常日／競馬開催日／直通便／運賃の収録範囲を再確認し、変換処理の注意文・対応カレンダーも更新してください。現状の注意文は今回の部分データに合わせています。

## APKの最終確認

```sh
bash gradlew :app:testDebugUnitTest :app:compileDebugAndroidTestKotlin :app:assembleDebug
python3 tools/check_apk_data.py app/build/outputs/apk/debug/app-debug.apk
```

Android Gradle Pluginはassets内の`.gz`を自動展開し拡張子を除去します。そのため圧縮京王DBは`keio.bundle`という名前にしています。圧縮形式はgzipです。最終チェックはAPK中のファイル名・内容がアプリの読み込み先と一致することも検証します。

## 未完了の確認

- JR・バス入りDB、地図本体または配布URLの受領と本番配布設定。
- 端末での初回展開、既存DBへの追記、実際のHTTPS配布先からの取得、通信断・容量不足・再起動時の動作。
- 京王の全時刻表・全運賃・競馬開催日・年末年始ダイヤの取得。
