   # OfflineTransitMap — 現在の引き継ぎ状況

最終更新: 2026-10-08
調査対象: 初回調査 `a0c7ea83c4dd4cb52e6414396c1db8d1da286ccf`、時刻表修正 `eedc9533bd395ce834df79bc54822464fae6575f`、京王バス追加 `bcde80da545876f5c072e78c478d1e1656fb3232`、Windows環境エラー・ビルド警告修正、小田急電鉄データ追加、小田急発車番線バグ修正、任意地点長押し目的地設定・ナビ開始。

このファイルを今後の優先引き継ぎ資料とする。リポジトリには元の資料が存在しなかったため、ユーザー添付の `PROJECT_STATUS.md` を末尾に保存した。現在の確認結果はこの冒頭部分を優先する。末尾の実機確認済み表記は以前の確認履歴であり、今回の修正後の動作確認を意味しない。

## 開発上の制約

- Kotlin / Jetpack Compose / MapLibre と既存のDB・UI構成を維持し、変更を最小限にする。
- 既存機能に影響する変更は実装前に説明する。削除・移動・大規模変更には理由を示す。
- 大容量の地図・GTFS・配布DBは外部サーバー/CDNから事前取得し、端末内へ保存する方針。Gitにはコード・設定・取得更新処理・引き継ぎ資料を置く。
- JR東日本の手動時刻表は将来の候補。現段階では全時刻表のDB化を開始しない。
- 2026-10-06のユーザー指示により、今後は修正・検証・この資料の更新後にコミットし、GitHubへpushする。手動貼り付けを通常の反映手順としない。
- このタスクの反映先は `coderabbit/review-offline-transit-project/895e6977`。実行環境でpushを許可されたタスク用ブランチを使い、完了報告に反映先とコミットを示す。
- pushが失敗した場合は未反映と明記し、原因を報告する。実機未確認などの検証上の制約も維持して記録する。

## 任意の場所長押しによる目的地設定・ナビ開始機能の追加（2026-10-08、最新）

### 実装した内容
1. **地図長押し検出と目的地ピン表示 (`MapLibreMapView.kt`, `RouteOverlay.kt`)**:
   - `MapLibreMap.addOnMapLongClickListener` を登録し、地図上の任意座標の長押しを検出。
   - スタイルに `destination` GeoJSONソースおよびマーカーレイヤー（`destination-circle-pulse`, `destination-circle`, `destination-name`）を追加。長押し地点に赤色のピン・サークルおよび「目的地」ラベルを鮮明に表示。
   - 地図の別地点タップ時や戻る操作時に目的地選択を解除するハンドラーを整備。
2. **ドア・ツー・ドア経路検索の実装 (`RouteSearch.kt`)**:
   - `findRoutesBetweenCoordinates` を追加。現在地（出発地）から長押し地点（目的地）の座標をもとに、
     - 出発地〜最寄り乗車駅への徒歩レグ（`fromName = "現在地"`, `walkMinutes`, `path`）
     - 公共交通機関（電車・バス）の乗換レグ群
     - 降車駅〜目的地への徒歩レグ（`toName = "目的地"`, `walkMinutes`, `path`）
     を自動で結合した完全なドア・ツー・ドア経路（`Itinerary`）を生成。
   - 近距離（2.5km以内）や公共交通機関が利用できない場合に対応する直接徒歩ルート生成ヘルパーを実装。
   - 地球楕円体面近似による高速な距離計算 `calcDistanceMeters` および `walkDurationMinutes` を提供。
3. **全行程徒歩ナビゲーション対応 (`Navigation.kt`)**:
   - `NavigationService.evaluate()` を拡張し、電車やバスに乗らない全行程徒歩ルート（`busIdx.isEmpty()`）でも目的地への距離・方位矢印・点線案内・到着判定（50m以内）が正確に動作するように改修。
4. **目的地案内カード UI (`DestinationCard.kt`, `MainActivity.kt`)**:
   - 地図長押し時に画面下部に `DestinationCard` がポップアップ表示。
   - 最寄り駅・バス停名、直線距離、徒歩所要時間を表示。
   - **「ナビ開始」ボタン**: 現在地から最速ルートを即座に計算し、ターンバイターンのナビゲーション（フォアグラウンドサービス、通知、画面上オーバーレイ）を1タップで開始。
   - **「ルート確認」ボタン**: 経路詳細パネル（`RouteDetailPanel`）を表示し、地図上のルートラインやタイムライン、運賃等を確認可能。
5. **テストの追加と検証 (`DestinationNavigationTest.kt`)**:
   - 距離計算、徒歩分数計算、目的地GeoJSON生成、直接徒歩Itinerary生成の単体テストを追加。

### 検証結果
- `.\gradlew.bat testDebugUnitTest`: **BUILD SUCCESSFUL** (全26単体テスト合格)
- `.\gradlew.bat compileDebugAndroidTestKotlin`: **BUILD SUCCESSFUL**
- `.\gradlew.bat assembleDebug`: **BUILD SUCCESSFUL**
- `python tools/check_apk_data.py`: **PASS**
- `python -m unittest discover tools/tests`: **Ran 23 tests, OK**

## 小田急電鉄の発車番線表示バグ修正（2026-10-08）

### 発生していた問題
- 小田急電鉄の各駅における時刻表・発車案内画面（出発シート等）で、発車番線が「OH01番線」「OE03番線」のように駅ナンバリング（stationCode）で表示されていた。

### 原因
- `tools/build_odakyu_data.py` の `stops` テーブル作成処理で、誤って `odpt:stationCode` を `stops.platform` カラムに挿入していた。
- アプリ側のUIロジック（`TimetableDb.kt` の `platformText()`、`DepartureSheet.kt`、`Navigation.kt`、`RoutePanel.kt` 等）は、`stops.platform` が空文字でない場合に「〜番線」と付加して表示する仕様となっている。
- 既存のJR東日本（711駅）および京王電鉄の鉄道データでは、のりば（番線）情報が存在しないため `stops.platform` は例外なく空文字（`''`）に統一されている（バス停のみのりば番号が入る）。

### 修正内容
1. **シード生成スクリプト修正 (`tools/build_odakyu_data.py`)**:
   - `stops` 挿入時の `platform` 値を `s.get('odpt:stationCode', '')` から `''` に修正。
2. **単体テスト追加 (`tools/tests/test_odakyu_data.py`)**:
   - `test_platform_is_empty_for_rail_stations` を追加し、小田急の `stops.platform` が全て空文字（駅ナンバリング誤混入がないこと）を検証。
3. **アセット再生成・検証**:
   - `odakyu.bundle`（確定gzip、1,289,054 bytes）および `odakyu-info.json` を再生成。
   - `python -m unittest tools/tests/test_odakyu_data.py`（6テスト PASS）
   - `.\gradlew.bat testDebugUnitTest`（全JVMテスト PASS）
   - `.\gradlew.bat compileDebugAndroidTestKotlin`（PASS）
   - `.\gradlew.bat assembleDebug`（BUILD SUCCESSFUL）
   - `python tools/check_apk_data.py`（PASS: APK内アセット検証合格）

## 小田急電鉄データの追加（2026-10-08）

### 実装した内容
1. **小田急電鉄データ取り込みツールの実装・最適化 (`tools/build_odakyu_data.py`)**:
   - ユーザー提供の ODPT データ（`odptRailway.json` 3路線、`odptStation.json` 72駅、`odptStationTimetable.json` 276時刻表・40,351発車時刻）および内閣府祝日CSVを解析。
   - `bisect`（二分探索）による高速トリップ連鎖マッチングを導入し、40,351件の発車イベントを4,475便・43,929停車時刻に瞬時に統合（所要時間約0.5秒）。
   - 小田原線（47駅）、江ノ島線（17駅）、多摩線（8駅）の各駅・急行・快速急行・準急・特急ロマンスカー（extra-fare別系統）を完全収録。
   - 祝日ダイヤ対応（2027年末まで）および年末年始（12/30〜1/3）運休制御。
   - 約1.3MBの確定gzip圧縮 bundle (`assets/bootstrap/odakyu.bundle`) およびメタデータ `odakyu-info.json` を生成。
2. **Androidアプリ本体への小田急データ統合 (`KeioDatabase.kt`, `OfflineDataSetup.kt`, `TimetableDb.kt`)**:
   - `KeioDatabase.kt` に `ODAKYU_PREFIX` (`ODPT_ODAKYU:`) を追加し、`merge`・`isBootstrapOnly`・`feeds` テーブル対応を拡張。
   - `OfflineDataSetup.kt` の初回展開およびシードマージループに `odakyu` を追加。アプリ初回起動時・更新時に自動的に既存の `timetable.db` へ小田急データ（全線72駅、4,475便）を安全・原子的（アトミック）にマージ。
   - `TimetableDb.kt` の `stationDataNote` で、小田急の駅タップ時に収録内容の案内メッセージを表示。
   - `RailStationIcons.kt` により、新宿駅などのJR共用駅は自動的に横並びアイコン (`station-rail-both`)、小田急単独駅は列車アイコン (`station-rail`) で地図上に表示。
3. **ビルド検証・テスト・配信パイプライン拡張 (`app/build.gradle.kts`, `check_apk_data.py`, `fetch_and_build_transit_data.py`)**:
   - `app/build.gradle.kts` の `verifyOfflineAssets` タスクに `odakyu.bundle` と `odakyu-info.json` の存在・サイズ検証を追加。
   - `tools/check_apk_data.py` でAPK内の小田急アセット同梱検証を追加。
   - `tools/fetch_and_build_transit_data.py` に小田急シードのマージオプション `--odakyu-bundle` を追加し、統合DBビルド時（全65,341便）に小田急を含めるよう拡張。
   - 小田急シード検証用単体テスト [`tools/tests/test_odakyu_data.py`](file:///C:/Users/hayat/AndroidStudioProjects/OfflineTransitMap/tools/tests/test_odakyu_data.py)（5テスト）を追加。
   - Androidテスト [`KeioDatabaseTest.kt`](file:///C:/Users/hayat/AndroidStudioProjects/OfflineTransitMap/app/src/androidTest/java/com/example/offlinetransitmap/KeioDatabaseTest.kt) に小田急データマージの検証（全65,341便、小田急6路線・72駅）を追加。

### 検証結果
- `python -m unittest tools/tests/test_odakyu_data.py`: **Ran 5 tests, OK**
- `python -m unittest discover tools/tests`: **Ran 22 tests, OK**
- `.\gradlew.bat testDebugUnitTest`: **BUILD SUCCESSFUL** (全JVM単体テスト成功)
- `.\gradlew.bat compileDebugAndroidTestKotlin`: **BUILD SUCCESSFUL**
- `.\gradlew.bat assembleDebug`: **BUILD SUCCESSFUL**
- `python tools/check_apk_data.py`: **PASS**

## GTFS定期自動更新・GitHub Actions配信パイプラインの実装（2026-10-07）

### 実装した内容
1. **GitHub Actions 自動化ワークフロー (`.github/workflows/update-transit-data.yml`)**:
   - 毎週日曜日 03:00 UTC（12:00 JST）の定期スケジュール（cron）および手動実行（`workflow_dispatch`）に対応。
   - **2種類のODPTトークン（Secrets）対応**:
     - `ODPT_ACCESS_TOKEN`: 通常の公共交通オープンデータセンター用（西東京バス等のバス事業者）。
     - `ODPT_CHALLENGE_TOKEN`: 東京公共交通オープンデータチャレンジ2026用（JR東日本・京王電鉄等の鉄道事業者）。
   - `tools/fetch_and_build_transit_data.py` を呼び出して、最新の京王バス・京王電鉄・西東京バス・JR東日本データを統合した最新 `timetable.db` を自動ビルド。
   - テスト（`tools/tests/`）によるDBの整合性（`PRAGMA integrity_check`、便数・参照整合性）を検証。
   - gzip圧縮した `timetable.db.gz`（約30MB）とメタデータマニフェスト `transit-manifest.json` を GitHub Releases（タグ `transit-data-latest`）に自動アップロード・更新。
2. **統合データビルド・マニフェスト生成スクリプト (`tools/fetch_and_build_transit_data.py`)**:
   - ODPT APIおよびチャレンジ2026 APIからのダウンロード関数（`download_odpt_gtfs`）および `--odpt-token`, `--odpt-challenge-token` オプションを実装。
   - ベースDB（JR東日本・西東京バス）と、各事業者シード（京王電鉄・京王バス）を純粋なPython/SQLiteで安全にマージ（計60,866便）。
   - 確定的なgzip圧縮（mtime=0）およびSHA-256・ファイルサイズ・有効期間・便数を記録した配布マニフェスト `transit-manifest.json` を出力。
   - パイプラインの単体テスト [`tools/tests/test_fetch_and_build.py`](file:///C:/Users/hayat/AndroidStudioProjects/OfflineTransitMap/tools/tests/test_fetch_and_build.py) を追加し、全17テスト合格を確認。
3. **Androidアプリ側 自動更新機構 (`TransitUpdateManager.kt`)**:
   - リモートの `transit-manifest.json` を照合し、新データがあるかをSHA-256・バージョンで判定（`checkForUpdate`）。
   - バックグラウンドで `timetable.db.gz` をダウンロードしながらストリーム展開・SHA-256照合。
   - SQLite検査（`PRAGMA quick_check`）とWAL未書き込みを確認の上、`DataFileIO.commit` による原子的差し替え（アトミックリプレイス）で安全に更新。失敗時は既存DBを完全保護（ロールバック保証）。
4. **設定画面 UI拡張 (`SettingsScreen.kt`, `AppSettings.kt`)**:
   - 「時刻表データの自動更新」設定カードを追加。
   - 「起動時に更新を確認」トグルスイッチ、手動「更新を確認」ボタン、プログレスバー表示、「最新版を適用」ボタンを配置。
   - ユニットテスト [`TransitUpdateManagerTest.kt`](file:///C:/Users/hayat/AndroidStudioProjects/OfflineTransitMap/app/src/test/java/com/example/offlinetransitmap/TransitUpdateManagerTest.kt) を追加。

### 検証結果
- `.\gradlew.bat testDebugUnitTest`: **BUILD SUCCESSFUL** (全22単体テスト成功)
- `.\gradlew.bat compileDebugAndroidTestKotlin`: **BUILD SUCCESSFUL**
- `.\gradlew.bat assembleDebug`: **BUILD SUCCESSFUL**
- `python -m unittest discover tools/tests`: **Ran 17 tests, OK**
- `python tools/check_apk_data.py`: **PASS**

### 制約・次に必要な作業
1. **GitHub Secretsの登録**:
   - GitHubリポジトリの `Settings > Secrets and variables > Actions` に以下を登録する：
     - `ODPT_ACCESS_TOKEN`: バス用トークン
     - `ODPT_CHALLENGE_TOKEN`: 鉄道用トークン（チャレンジ2026）
2. **変更のコミット＆プッシュ**:
   - ローカルの変更（ワークフロー含む）をGitHubへpushすると、GitHub上の「Actions」タブに「Update Transit Data」が自動表示される。
3. **初回のActions実行とRelease確認**:
   - Actionsで「Update Transit Data」を手動実行し、Releases（`transit-data-latest`）にデータが公開されることを確認。
4. **アプリでの更新確認**:
   - アプリの設定画面から「更新を確認」をタップして反映を確認。

## Windows環境エラー・ビルド警告の修正（2026-10-07）

### 今回修正した問題
1. **Pythonユニットテスト (`tools/tests`) の UnicodeDecodeError（7件のエラー）**:
   - `test_keio_bus_data.py` および `test_keio_data.py` のテスト実行時に、UTF-8で保存されたメタデータJSON（`keio-info.json`, `keio-bus-info.json`）の読み込みで `UnicodeDecodeError: 'cp932' codec can't decode byte ...` が発生してテストが失敗していた。
2. **Kotlinコンパイラ警告 (`Navigation.kt:389`)**:
   - `walking` 変数判定内の `boardPoint != null` とその後の `if (walking && boardPoint != null)` の重複判定により、`Condition is always 'true'` 警告が発生していた。
3. **Androidシステムバー色の非推奨警告 (`MainActivity.kt:75-76`)**:
   - Android 15 (API 35+) 移行で非推奨となった `window.statusBarColor` / `window.navigationBarColor` による警告が発生していた。

### 原因
1. **Python側**:
   - Windows環境におけるPythonの既定ファイル読み書きエンコーディングが `cp932`（Shift_JIS）であるため、明示的に `encoding='utf-8'` を指定せずに `Path.read_text()` または `Path.write_text()` を実行すると、日本語文字列を含むUTF-8ファイルでデコード/エンコード不整合が発生していた。
2. **Kotlin側**:
   - `Navigation.kt` で `val walking = boardDist != null && boardDist > APPROACH_M && boardPoint != null` と定義された後、`if (walking && boardPoint != null)` と記述されていたため、後者の `boardPoint != null` が静的解析で常に真と判定されていた。
   - `MainActivity.kt` で古い互換API呼び出しに対して警告抑制アノテーションが付与されていなかった。

### 変更したファイル
- [tools/tests/test_keio_bus_data.py](file:///C:/Users/hayat/AndroidStudioProjects/OfflineTransitMap/tools/tests/test_keio_bus_data.py#L20): `read_text(encoding='utf-8')` を指定
- [tools/tests/test_keio_data.py](file:///C:/Users/hayat/AndroidStudioProjects/OfflineTransitMap/tools/tests/test_keio_data.py#L18): `read_text(encoding='utf-8')` を指定
- [tools/tests/test_data_configuration.py](file:///C:/Users/hayat/AndroidStudioProjects/OfflineTransitMap/tools/tests/test_data_configuration.py#L24): `read_text(encoding='utf-8')` を指定
- [tools/build_keio_bus_data.py](file:///C:/Users/hayat/AndroidStudioProjects/OfflineTransitMap/tools/build_keio_bus_data.py#L148): `write_text(..., encoding='utf-8')` を指定
- [tools/build_keio_data.py](file:///C:/Users/hayat/AndroidStudioProjects/OfflineTransitMap/tools/build_keio_data.py#L140): `write_text(..., encoding='utf-8')` を指定
- [tools/configure_data_files.py](file:///C:/Users/hayat/AndroidStudioProjects/OfflineTransitMap/tools/configure_data_files.py#L57): `write_text(..., encoding='utf-8')` を指定
- [app/src/main/java/com/example/offlinetransitmap/Navigation.kt](file:///C:/Users/hayat/AndroidStudioProjects/OfflineTransitMap/app/src/main/java/com/example/offlinetransitmap/Navigation.kt#L388): 冗長条件判定を解消しスマートキャストを活用
- [app/src/main/java/com/example/offlinetransitmap/MainActivity.kt](file:///C:/Users/hayat/AndroidStudioProjects/OfflineTransitMap/app/src/main/java/com/example/offlinetransitmap/MainActivity.kt#L75-L77): `@Suppress("DEPRECATION")` を追加

### ビルド結果
- `.\gradlew.bat assembleDebug`: **BUILD SUCCESSFUL** (APK生成成功)
- `.\gradlew.bat testDebugUnitTest`: **BUILD SUCCESSFUL** (全JVM単体テスト成功)
- `.\gradlew.bat compileDebugAndroidTestKotlin`: **BUILD SUCCESSFUL** (AndroidTestコンパイル成功)
- `.\gradlew.bat compileDebugKotlin --rerun-tasks`: **BUILD SUCCESSFUL** (警告 0 件)
- `python -m unittest discover tools/tests`: **Ran 16 tests, OK** (全16件のテスト成功、失敗0件)
- `python tools/check_apk_data.py`: **PASS** (APK内同梱アセットのハッシュ・整合性検証合格)

### まだ残っている問題
- Android実機での動作確認（新規インストール、既存DBからの更新、オフライン地図・バス停表示、乗換案内、通知・ナビ操作など）は開発環境に実機がないため未実施。
- 京王バスGTFSの有効期限（2026-10-01〜2026-12-31）および将来の定期更新手順の整備。
- 道路に沿ったオフラインナビ（現在は直線ベースの簡易案内）。

### 次に行うべき作業
1. 実機端末での動作確認。
2. 将来のデータ定期更新手順・恒久配布先の検討。
3. 道路ネットワークデータを用いた道路沿いナビゲーションエンジンの導入検討。

## 京王バス追加・実ファイル同梱（2026-10-06、最新）

### 実装・確認した内容

- ユーザーのGigaFile共有から `timetable.db`（50,499,584 bytes）、`tokyo.pmtiles`（172,501,520 bytes）、京王バスGTFS（5,815,586 bytes）を取得。HTMLのエラーページではなく各形式の実体・サイズ・ハッシュを確認。元ファイルは作業環境の `inputs/transit-data/` に保持。
- 既存DBはv5。JR東日本18,520便／西東京バス等11,962便、合計30,482便。`feeds` はprefix/name形式、calendarはseedと列順が異なり、faresには複合主キーがある。既存の名前指定INSERTを維持し、フィード情報の形式差だけ吸収した。
- `tools/build_keio_bus_data.py` を追加。京王バス254系統・29,550便・447,000停車時刻、2,927のりば／1,529バス停グループ、運賃106,189組を既存v5形式に変換。かな・のりば・行先・乗降制限・24時台・祝日例外を維持。
- `KeioDatabase.merge` を京王バスにも共用。ID接頭辞・更新キーを分け、既存データを残して追記。同名・同種・700m以内の既存グループへ合流。従来の地図アイコン・表示倍率・時刻表／検索UIを使用する。
- 初回起動で地図・既存DBを展開し、京王電鉄→京王バスをコピー上で統合。既存端末にも京王バス部分を追加。データ欠落時に生成された京王のみのDBから本体DBへ移行する判定も維持。
- `bootstrap/data-files.json` を実ファイルの同梱設定に更新。全データ入りAPKを作成し、APK内部の実ファイルとサイズ・SHA-256を照合。APK約250MiB、versionCode2／versionName1.1。初回起動時にファイルが揃う構成で、端末ごとの手動コピー不要。
- 大容量DB・地図・京王バスseedはGit対象外。配布担当者が元ファイルから準備する[手順](docs/DATA_DISTRIBUTION.md)を更新し、同梱ファイル不足はビルド時に検出する。期限付き共有URLを端末のダウンロード先には使用しない。

### 検証結果

- Python16テスト、JVM19テスト成功。Androidテストはコンパイル成功、端末での実行は未確認。デバッグAPK作成と同梱ファイル名・サイズ・ハッシュ照合成功。
- 実DBに対し実装から取り出したSQLで京王電鉄／京王バスを2回統合し、全60,866便を確認。既存の全テーブル行をハッシュ比較して不変を確認。重複・参照切れなし、高尾の鉄道グループ／高尾駅南口のバスグループ、平日／祝日の京王バス出発検索、エラー時のロールバックを検証。
- 既存DBに白丸の鉄道／バス共通グループが元から存在したため、その状態は保持。高尾という同名のバス停は高尾駅の鉄道グループとは分離して検証した。

### 制約・次に必要な作業

- 京王バスGTFSの有効期間は **2026-10-01〜2026-12-31**。出発シート・初回案内・設定で確認できる。全体の有効期間表示とは別に事業者別の制約を示す。
- 経由ゾーン条件付きの3系統（route_id609／1171／1828）の運賃は現検索形式で表せず、金額を表示しない。便・停留所は取り込む。均一運賃は実ゾーンに展開し、不明額を推測しない。
- 京王電鉄の部分データ／不足便・運賃・年末年始等の制約は引き続き有効。京王バスGTFSにshapesはなく、道路に沿ったナビは未実装。
- **実機での新規インストール、既存端末への更新、オフライン地図・バス停の表示、時刻表・乗換操作、容量不足時の確認は未完了。** Android端末がないため、計算・DB・ビルド検証と実機確認を分ける。
- 今回の受領リンクはGTFSが2026-10-10、DB・地図が2026-10-12まで。元データを配布担当者側で保存し、将来の定期更新には恒久配布先と次期GTFSが必要。一般の自動更新は未実装。

## 京王の追加と初回ファイル準備（2026-10-06）

### 実装済み

- ユーザー提供のODPT形式JSON4種を解析。7路線・76駅レコード（69駅相当）、時刻表1,000件、運賃1,000組。路線・駅の全レコードを取り込み、通常日時刻表834件を既存のv5形式へ変換した。
- `tools/build_keio_data.py` で再生成できる約300KBの圧縮DBを `assets/bootstrap/keio.bundle` に同梱。初回起動時に展開する。新規端末には京王入りDBを作り、既存のJR・バス入りDBにはコピー上で追記し、検証後に差し替える。元のデータを無条件に上書きしない。
- 便番号の衝突回避・二重取り込み防止・同名700m以内の鉄道駅との既存グループ合流を実装。全京王駅に `station_operators` 補助情報を持たせ、時刻表未収録の高尾などもJR・私鉄横並び表示の判定対象にした。
- `OfflineDataSetup.kt` / `KeioDatabase.kt` / `DataFileIO.kt` で、地図と本体DBの同梱・HTTPS取得、進捗表示、再試行、サイズ・SHA-256照合、DB／PMTilesヘッダーの検証、一時ファイルからの原子的な差し替えを実装。通信できない場合は同梱・保存済みデータで開始できる。
- アプリの地図・DBを開く前に準備する。京王のみの自動生成DBは後から設定された本体DBへ移行可能。通常の既存本体DB／地図は自動上書きしない。定期自動更新や取得途中からの再開は未実装。
- 地図未取得時はオンラインのデモ地図への接続をやめ、背景なしで駅・経路をオフライン表示する。初回案内と設定画面に不足・収録範囲を表示する。京王の出発シートにも部分データの説明を追加した。
- 配布担当者が `tools/configure_data_files.py` で実ファイルのサイズ・ハッシュと同梱／URLを設定し、同じAPKを各端末へ導入する方式。[配布手順](docs/DATA_DISTRIBUTION.md)を参照。大容量の同梱ファイルはGit対象外。

### データ・配布上の未完了事項

- この節の初期実装時点では本体DB・地図が未受領だったが、上の「京王バス追加・実ファイル同梱」で受領・同梱設定を完了。
- 時刻表は井の頭線・動物園線・京王線の一部のみで、高尾線・相模原線等は未収録。競馬開催日用166便は日程がないため無効化。提供外の時刻・運賃を推測して補完しない。19件の直通先時刻表リンクは参照先が欠け、路線間の直通便連結も未対応。
- 適用終了日の記載なし。内閣府CSVに基づく祝日カレンダーを2027年末まで収録するが、時刻表がその日まで有効という意味ではない。特別ダイヤ未提供の12/30〜1/3は京王の検索対象外。京王ライナーの総額を普通運賃だけで確定しない。
- 全件版の時刻表・運賃、競馬開催日・年末年始ダイヤ、提供データの有効期間を確認する必要がある。

### 検証と次の作業

- Pythonのデータ／配布設定10テスト、JVM19テストが成功。AndroidのDB統合・初回展開テストを追加してコンパイル成功、デバッグAPK作成成功。実ソースから取り出したSQLで、JR便の維持・便番号再割当・高尾グループ合流・二重取り込み防止・エラー時のロールバックを確認。
- APK検査でAGPが`.gz`を自動展開して名前を変える問題を発見したため、同梱ファイルを`.bundle`へ変更。`tools/check_apk_data.py`で最終APKのファイル名・内容を確認する手順を追加。
- 実機の初回準備・既存の実DBとの統合・背景地図・実HTTPS配布先での取得は未確認。端末、既存DB、地図、実配布URLがないため。
- 次は本体DB・地図の受領→本番配布設定→実機で新規／更新／通信断／オフライン再起動を確認。前からの道路に沿うオフラインナビは未実装のまま。

## JR・私鉄の駅アイコン（2026-10-06）

- 添付のJR東日本マークと列車画像を同梱画像として使用。画像外周の背景だけを透過し、1個20dpで表示する。JR東日本はJRマーク、他の鉄道・会社不明は列車アイコン。同じ駅グループでJR東日本と他社の両方が判別できた場合は、JRを左・列車を右に2dp間隔で横並び（全幅42dp）にする。
- `TimetableDb.stationsGeoJson()` で、既存の駅グループと `stop_times → trips → routes.operator` から会社名を取得し、`rail_icon` 属性を追加。DBファイルのスキーマ変更なし。`stations.kind=rail`、路線種別0/1/2の便を対象とし、バスは会社判定に含めない。終点・降車専用駅も対象。運行日の絞り込みはしない。
- `RailStationIcons.kt` でJR東日本の略称・正式名称・英語名・全半角を判定。駅IDや駅名から会社を推測しない。会社が不明・取得できないときは共通の列車アイコンで駅を残す。旧DBで `kind` がない場合は従来のバス扱いを維持する。
- 鉄道駅の表示開始は通常のズーム10から11へ。駅名の文字・配置・ズーム12からの表示、バス停・施設・経路表示、既存のタップ対象と判定範囲を維持する。
- 駅データと会社判定を `Dispatchers.IO` で読み込み、読み込み後に既存の地図ソースを更新する。地図を再作成せず、会社情報の問い合わせで画面を止めない。
- 検証: `:app:testDebugUnitTest` 成功（新規6件を含む計13件）。`:app:compileDebugAndroidTestKotlin` 成功（GeoJSONの結合・バス除外・終点・代表座標・グループ分離・旧DBを確認する4件追加、実行は未確認）。実ソースのSQLを合成SQLiteで実行し、上記の会社分類に渡す結果と座標集計を確認。既存出発検索SQLの不変、地図JSON・画像登録・縮尺境界・横並び配置を確認し、実画像のライト／ダーク背景プレビューを作成した。
- **その後の対応**: 京王のODPTデータを受領し、上記の初回準備処理で追記するようにした。既存JR・バスの実DBは未受領。京王以外の私鉄はデータ未収録なら横並びにならない。
- 次に行うこと: 実機でJRのみ・私鉄のみ・共用駅の表示とタップを確認。私鉄未収録なら利用可能なデータと取り込み処理を確認する。実機・実DB・PMTilesがないため現時点では実表示、会社名の実値、読み込み時間は未確認。

## バス停表示の調整（2026-10-06）

- バス停レイヤー `stations` を赤丸から同梱VectorDrawableのバス停標識へ変更。赤い標識・白いバス・支柱の記号で、ライト／ダーク地図とも白い縁で識別する。通信なしで表示。
- 外寸は14dp。以前の赤丸は輪郭込み約17dpだったため、約18%縮小。
- バス停アイコンの表示開始を通常のズーム12から13へ変更。`BUS_MIN_ZOOM=13` をバス側だけに適用。バス停名は従来どおりズーム14.5から、同じ文字サイズ・配置で表示する。
- バス停のデータ・フィルター・レイヤーID・40pxのタップ判定範囲を維持し、出発時刻表の操作を接続したまま。アイコン同士の重なりによる間引きを行わず、名称の配置を妨げない設定。鉄道駅や施設アイコンの表示条件は維持。
- 検証: `:app:compileDebugKotlin` 成功（画像リソース処理を含む）。スタイルJSON、表示開始境界、バス停名／鉄道／施設／経路レイヤーの不変性、画像登録、寸法、タップ範囲を確認。実際のベクターパスのライト／ダーク背景プレビューも確認。
- 未確認: Android実機での縮尺・密集時の見え方・タップ操作。端末と実PMTilesがないため、次に実機で確認して必要に応じてサイズ／ズームを微調整する。

## 設定画面の追加（2026-10-06）

- 通常画面右上の「設定」から開き、「戻る」または端末の戻る操作で閉じる。既存の地図は保持し、検索結果や選択経路を維持する。
- 表示テーマ: ライト／ダーク／端末に合わせる。初期値は従来どおりライト。既存Composeテーマを接続し、保存済みPMTiles地図の背景・道路・文字も配色変更する。駅・バス停・経路の識別色と施設アイコンの寸法は維持。オンラインのデモ地図は配色変更の対象外。
- 地図の表示: 施設名と施設マーク（アイコン・点）を個別に表示／非表示。初期値は両方表示。駅・バス停・経路は対象外。
- ナビゲーション: 「ナビ中は画面を消さない」を追加。初期値OFF。アプリ表示中のナビに適用し、終了時は解除する。
- オフラインデータ: 既存の地図・時刻表ファイルの保存有無と容量を確認できる。内容の妥当性・収録範囲・有効期間を保証する表示ではない。
- `AppSettings.kt` のSharedPreferencesで設定を保存し、再起動時に復元。`SettingsScreen.kt` に項目を集約。`MapAppearance.kt` で読み込んだスタイルを更新し、カメラや地図自体を再作成しない。既存DBや経路検索方式、依存ライブラリの変更なし。
- 検証: `:app:testDebugUnitTest` 成功（新規6件を含む計7件）。`:app:compileDebugAndroidTestKotlin` 成功（設定保存・復元の3件を追加）。設定接続以外のMapLibre本体・スタイル・カメラ処理が不変であることも確認。
- 未確認: Android実機での設定操作・再起動後の復元・ダーク地図の見え方・施設表示4通り・ナビ中の画面点灯。端末と実PMTilesがないため、Androidテストはコンパイルまで。システムバー色の互換APIに非推奨警告が2件あるがビルド成功。
- 次の作業: 実機で上記項目を確認。その後、以下の道路に沿うオフライン案内の設計・データ取得を進める。道路ナビは現時点でも直線の簡易版。

## 進行中: 施設名の詳細化と道路に沿うナビ（2026-10-06）

ユーザーはGoogle Mapsのような施設名の詳細表示と、直線ではなく道路に沿う案内を希望している。追加回答で、徒歩・自動車の道路案内とバス・電車の実際の経路表示をすべてオフラインで実現することが理想と確認した。徒歩と既存乗換検索の接続から段階的に進める。既存UI・MapLibre・PMTiles・時刻表DB・乗換検索を活かす。

### 確認した現在の制約

- 添付 `map-info.txt` / `map-metadata.txt` を確認済み。PMTiles v3 / MVT / gzip、Protomaps Basemap 4.15.2、範囲は東経138.94〜139.92・北緯35.50〜35.90、最大ズーム15。OSM更新時刻はメタデータ上2026-09-30T04:00:00Z。
- `pois` に `kind` / `kind_detail` / `name` / `name:ja` 等の属性宣言がある。カテゴリ値の一覧・個別地点の収録までは確認できていない。施設名と新アイコンは従来の施設名と同じズーム15.5以上（タイルの拡大表示）で表示する。
- Protomapsの公式仕様ではPOIはOSMの一部の情報を選んだものであり、地図スタイルだけで未収録の施設を追加することはできない。現在のファイルは4.15.2。最新の生成プログラムと同じ収録条件と決めつけず、実データのカテゴリ値に応じて扱う。
- `NavigationScreen.navLineGeoJson()` は現在地と目標を2点で結び、`NavigationService` は直線距離と時刻で案内する。`RouteSearch` の徒歩距離・時間も直線距離由来。
- 乗車区間の `RouteLeg.path` も停車駅の座標を結んでおり、道路・線路の実形状ではない。徒歩用の道路探索をそのままバス/鉄道の経路に流用しない。

### 今回実施した範囲

- 前回は `MapLibreMapView.kt` の字形URIを修正済み。今回は以下の施設アイコンを追加した。表示倍率・既存ナビ方式は変更していない。
- 字形URI修正時はスタイルJSON構文・レイヤーID一意性・実アセットの存在を確認済み。今回は以下のカテゴリ別表示の検証を追加した。
- `:app:compileDebugKotlin` 成功。実機表示は未確認。

### 施設アイコンの追加（2026-10-06）

ユーザー希望: 施設を一目で判別できる記号にし、サイズ等を維持する。追加指示により交差点を🚥型に変更し、公園・コンビニ・神社なども大まかにアイコン化する。

- 同梱VectorDrawable 12点と `PoiMapIcons.kt` を追加。MapLibreのStyle.Builderに画像を登録し、既存の `pois` に分類別記号を重ねる。画像配信やネットワーク依存は追加していない。
- 記号の外寸は9dp（既存の施設点の半径3.5 + 輪郭1の直径に合わせた）。名前のスタイル値 `text-size: 11`・配置オフセット、駅/バス停の丸、カメラと縮尺を維持。
- 飲食店（restaurant/cafe/fast_food/food_court/bar/pub）: 茶色の食器。
- 医療施設（hospital/clinic/doctors/dentist/pharmacy）: 青地に白い医療マーク。
- 交差点/信号（junction/intersection/traffic_signals）: 横並びの赤・黄・緑の信号機型記号。交差点の凡例として使用し、リアルタイムの信号状態を表さない。
- 踏切（level_crossing）: 黄黒の交差記号。単なる crossing は横断歩道等にも使われるため踏切扱いしない。
- 公園・庭園・遊び場・自然公園など: 緑色の木。
- コンビニ・スーパー・商店・パン屋など: 買い物袋。
- 神社: 鳥居。`kind=shrine`、または `kind=place_of_worship` かつ `kind_detail=shinto` のときだけ適用する。名称から宗教を推定しない。
- 寺院・教会など宗教施設、役所・郵便局・図書館・銀行など: 共通の建物。神道と判別できない宗教施設を鳥居にしない。
- 学校・大学・幼稚園: 本。駐車場/駐輪場: P。トイレ: WC。ホテルなど宿泊施設: ベッド。
- 名前のない対象地点も記号を表示する。未知の種類は従来の青い点と名称表示を維持。既存の点はアイコンが衝突で省略された場合の目印にもなる。
- 交差点/踏切のカテゴリが実データに存在する保証はない。道路や線路の見かけの交差から生成せず、未収録なら別の地点データ取得・更新が必要。
- 12種類の画像は実際のベクターパスから描画したプレビューで確認。分類の118ケース（59種類の名前あり/なし）、神道・他宗教・詳細不明の区別、対象外分類、線ジオメトリの除外、画像登録、既存サイズ/カメラ維持を検証。最終の `:app:compileDebugKotlin`（リソース処理を含む）成功。実機・実PMTiles上での見え方は未確認。

### 次の設計方針（未実装）

1. 現在のPMTilesの取得元・生成手順またはファイルを確認し、施設の欠落と描画条件の問題を分ける。収録されている名前やカテゴリを活かし、足りない施設情報は地域別の配布データを整備する。
2. オフラインで道路を探索するための道路ネットワークとエンジンを選ぶ。地域データは外部配布・端末保存し、Gitには取得更新処理等を保存する。表示用タイルだけを見て通行可能と判断しない。
3. 徒歩なら、道路沿いの距離・時間・通行条件を既存の乗換検索にも反映し、経路線だけを変更して乗換時刻が不正確になることを避ける。
4. 現在地からの道路経路、曲がる地点、残距離、経路外れの検出・再検索を既存のナビ画面・サービスへ接続する。データ不足や経路取得失敗時には直線を正確な道路案内として表示しない。
5. 乗車中の経路も必要な場合は、GTFSの形状データ等の有無を確認して別途扱う。対象が運転案内なら道路規制を含め要件を追加確認する。

### 地図情報の受領状況

- ユーザーはgo-pmtilesのexeを使ったとのこと。全PMTilesの添付ではエラーになったため、小さな情報ファイルの共有を案内した。
- `map-info.txt` / `map-metadata.txt` を受領し、上記の範囲・版・レイヤー定義を確認済み。元の地図アーカイブや取得URLは未受領。
- 個別施設の種類や収録を調べる必要が生じたら、公式 `pmtiles tile` 等で小範囲の実タイルを確認する。メタデータの属性宣言だけで全施設の存在を断定しない。
- 道路に沿う案内・所要時間計算には別途道路ネットワークとエンジンが必要で、今回のアイコン追加には含まれない。引き続き徒歩と既存乗換検索の接続を優先し、その後自動車や乗車経路へ広げる。

参考に確認した一次資料:
- PMTiles CLIオプション確認: https://github.com/protomaps/go-pmtiles/blob/v1.31.2/main.go
- Protomaps Basemap Layers: https://docs.protomaps.com/basemaps/layers
- オフライン探索エンジン候補の調査: https://github.com/abrensch/brouter （採用は未決定）

## 初回調査のまとめ

### 現在の実装状況

単一の `app` モジュール。主要Kotlin 11ファイルとテーマ3ファイル、Manifest、Gradle設定、アセット、テンプレートテストを確認した。追加の `AGENTS.md`、`knowledge-base/`、リポジトリ固有のコードガイドは見つからなかった。

- `MainActivity.kt`: 地図・検索・出発シート・経路パネル・ナビの切り替え、位置/通知権限。
- `MapLibreMapView.kt`: 端末内 `maps/tokyo.pmtiles`、GeoJSONの駅/バス停、経路と現在地。ファイルがない場合はオンラインのMapLibreデモ地図へ接続する。
- `TimetableDb.kt`: 外部ファイル領域 `timetable/timetable.db` を読み取り専用で開く。駅グループ、出発一覧、便の停車駅。種別列と駅種類列は存在確認による互換処理がある。
- `RouteSearch.kt` / `RouteSearchScreen.kt`: SQLiteから運行日・便・停車時刻を読み、駅グループを使うラウンド式探索。最大4便、通常の徒歩乗換300m、6時間先まで、3種の並べ替え。
- `DepartureSheet.kt` / `RoutePanel.kt` / `RouteOverlay.kt`: 一覧と便詳細、経路詳細、停留所を直線で結ぶ経路表示。
- `Navigation.kt` / `NavigationScreen.kt`: フォアグラウンドサービス、時刻と位置による通知、直線方向を示す簡易徒歩案内。
- GTFSからSQLiteを生成する `gtfs_to_db.py` は添付資料に説明があるが、このリポジトリにはない。実際のDB・GTFS・PMTilesも未提供で、件数やスキーマの実データ検査は未実施。

### 現在できていること

ローカル地図・駅表示、出発一覧と便詳細、バス/JRの経路検索、既知のバス運賃合計、日時指定、種別表示、経路描画、簡易ナビのコードがある。添付資料では地図・出発一覧・バス検索等に過去の実機確認があるが、今回ユーザーから種別追加後の出発表示不具合が報告された。

### 未実装・制約

- データのアプリ内ダウンロード、自動更新、更新時の検証・安全な差し替え。
- JR運賃、全列車の確実な種別、JR駅のかな検索、道路沿いの徒歩経路。
- 地図欠落時にも使える完全オフラインの初回導線。
- 種別の確定値/推定値の由来は、今回確認したアプリの表示モデルでは区別しない。
- UIの全面変更や追加地図詳細化は未着手。現状の操作感を優先する。

### 問題点

1. **今回修正**: `TimetableDb.queryDay()` のSQL内に `${'$'}trainTypeColumn` とドル文字がエスケープされており、実際には `$trainTypeColumn` がSQLへ残っていた（下記参照）。SQLiteはこれを追加のバインドパラメータとして扱い、駅・運行日の引数がずれる。バスが空になる、一部の同一駅グループの電車だけ出る、種別に駅ID `JR_1301` が出る現象を合成データで再現した。
2. **地図詳細化の初期修正済み**: フォント参照の余分な `fonts/` を除き、`asset://{fontstack}/{range}.pbf` と既存の `assets/NotoSansRegular/0-255.pbf` を一致させた。アセットの移動は行っていない。
3. 時刻表の有効期間表示は全feedの最小開始日〜最大終了日で、バスだけの期限切れを区別しない。画像の `2026/3/14〜2027/3/12` だけではバスデータの有無を判断できない。
4. PMTilesのレイヤー定義は添付メタデータで確認済み。個別地点のカテゴリ・実機描画と、バス/JRの元データ・種別推定の正しさは未検証。
5. 既存テストはテンプレートのみだった。今回、DB出発読込の回帰テストを追加した。

## 今回の修正: 種別追加後に出発一覧が欠落する問題

- アプリコードの変更は `app/src/main/java/com/example/offlinetransitmap/TimetableDb.kt` のSQL SELECT内1行。
- 変更前: `st.trip, st.seq, ${'$'}trainTypeColumn`
- 変更後: `st.trip, st.seq, $trainTypeColumn`
- Kotlin側で `t.train_type` または `NULL` を展開し、SQLパラメータは駅IDと運行日IDだけにする。
- 種別列がないDBへの既存フォールバックを維持。DB再生成やアプリデータ削除は、このSQL修正には不要。
- UIの種別欄は読込値をそのまま表示していたため、今回レイアウト変更は行っていない。実DBに誤った種別が保存されている場合は別途調査が必要。
- `app/src/androidTest/java/com/example/offlinetransitmap/TimetableDbTest.kt` を追加。実データに触れない一時DBで、バス、グループ化された電車と種別、種別列のない旧DBを検証する。運休サービス・降車専用行の除外も確認対象。

### 検証状況

- 修正前/後の実SQLとテスト用データを用いたネイティブSQLite検証: 4ケース成功。バス0件→1件、電車1件→2件、誤った `JR_1301` → `快速` / `普通`。旧DBでは種別空欄のまま便を取得できる。
- このSQLite検証はKotlin全体のコンパイルやAndroid画面検証の代替ではない。
- アプリ本体と追加したAndroidテストのKotlinコンパイル: 成功（Gradle 9.6.0 / OpenJDK 25.0.2 / SDK Platform 37.0 revision 2 / Build-Tools 36.0.0）。`Navigation.kt:389` に既存の常にtrueとなる条件の警告あり。追加したAndroidテストは接続端末がないため未実行。
- 修正後の実機・実DB検証は未実施。

## 次に優先すべき作業

1. 修正ビルドを既存データを保持して実機に適用し、馬込松木のバス出発、八王子と他のJR駅の出発・種別、便詳細、既存経路検索を確認する。
2. 症状が残る場合は、端末上の `timetable.db` とLogcatを確認する。コードだけで実DBの欠損を推測して作り直さない。
3. `gtfs_to_db.py` を回収して生成・追記・種別推定処理を確認し、再現可能な更新手順を整える。巨大データはGitに入れない。
4. フォント参照は修正済み。実PMTilesを使って文字・駅種別・施設名表示を確認する。
5. 安定化後に、既存のローカルファイル読込を活かすダウンロード・有効期間管理・安全な更新を設計する。

### JR手動時刻表についての暫定提案（未実装）

既存の `stations / stops / routes / trips / stop_times / calendar / calendar_dates` を読込用の共通モデルとして活かす。手動整備データもこの形式へ変換する案を第一候補にし、GTFSと重複する便の優先順位、識別子、出典・更新日、種別の確定/推定の管理は生成スクリプトと実DBを確認してから決める。新しい検索エンジンや全時刻表DB化は開始しない。

---

## 添付の原資料（過去の引き継ぎ記録・以下は原文）

# PROJECT_STATUS.md — オフライン乗換マップ

最終更新: 2026-10-06(アプリを編集するたびに、このファイルを上書きして更新する)

## 1. 概要

- 個人用のAndroidアプリ。**完全オフライン**で、地図の表示・駅/バス停の出発時刻・乗換案内(バス+JR東日本の首都圏)・料金(バスのみ)・軽いナビ(通知)を行う。
- 作業の進め方: ユーザーが Android Studio で**手動でコードを貼り付ける**。コード修正は、必ず**対象ファイルのパス**を示し、貼り付け可能なコードを出す。部分変更は挿入位置/置き換え範囲を明記し、複数ファイルは**ファイルごと**に分ける。既存の仕様を維持し、不明点は修正前に質問する。
- **Claudeの作業環境ではAndroidアプリをコンパイルできない**。コードは未コンパイルで渡している。時刻表DBの作成と検索ロジックは、Pythonで実データを使って検証している。

## 2. 環境

| 項目 | 内容 |
|---|---|
| パッケージ | `com.example.offlinetransitmap` |
| minSdk / compileSdk / targetSdk | 26 / 37 / 37 |
| AGP / Kotlin / Compose BOM | 9.4.1 / 2.2.10 / 2026.02.01 |
| 地図 | MapLibre Native 13.4.1(`org.maplibre.android.*`)、PMTiles(`tokyo.pmtiles`) |
| アイコン | `material-icons-core` のみ(`Icons.Default.Search` を使用)。現在地アイコンはコードで描画 |
| PC | Windows、Android Studio。端末へは `adb push` でデータを送る |

## 3. 端末に置くファイル

アプリ(`com.example.offlinetransitmap`)の外部ファイル領域。**フォルダはアプリが自動で作る。`adb shell mkdir` で先に作らない**(アプリから見えなくなる)。

| 端末内のパス | 内容 |
|---|---|
| `/sdcard/Android/data/com.example.offlinetransitmap/files/maps/tokyo.pmtiles` | 地図(約172MB、Protomaps系のOSM由来ベクタータイル) |
| `/sdcard/Android/data/com.example.offlinetransitmap/files/timetable/timetable.db` | 時刻表DB(現在 **バージョン5**、約50MB)。ファイルは `/mnt/user-data/outputs/timetable.db` |

アプリ内のアセット(地図の文字に必要。**ユーザーが1回だけ手動で置く**):
`app/src/main/assets/fonts/NotoSansRegular/0-255.pbf`(取得元: `https://protomaps.github.io/basemaps-assets/fonts/Noto%20Sans%20Regular/0-255.pbf`)

## 4. ソースファイル(`app/src/main/java/com/example/offlinetransitmap/`)

| ファイル | 役割 |
|---|---|
| `MainActivity.kt` | 画面全体。地図・FAB(現在地に戻る/虫眼鏡)・位置情報と通知の許可・検索/経路パネル/ナビ画面の切り替え・駅の出発シート |
| `MapLibreMapView.kt` | MapLibreの地図(Compose)。スタイルJSON、駅の丸(バス/電車で色分け)・タップ判定、現在地マーク、経路の線、ナビの追従(進行方向が上)、現在地へ移動 |
| `DemoData.kt` | `Departure` / `TripStop` のデータクラス、サンプルデータ(DBが無いときのフォールバック) |
| `DepartureSheet.kt` | 駅をタップしたときの下のシート(出発一覧、行間余裕あり)、便の詳細(経由地・時刻の縦の流れ) |
| `TimetableDb.kt` | 時刻表DBの読み込み。駅の丸(GeoJSON、グループごと1点+種類)、出発検索(同じ駅グループをまとめる)、便の経由地 |
| `RouteSearch.kt` | 経路検索の中核(`RouteSearcher`)。駅グループ・徒歩の乗換・RAPTOR風の探索・運賃・並べ替え(早い順/乗換少/料金安い順) |
| `RouteSearchScreen.kt` | 検索画面(出発地・目的地、出発日時の指定、3種の並べ替え、結果カード)。状態は `RouteSearchState` で保持 |
| `RoutePanel.kt` | 経路を選んだときの下の詳細パネル(縦の流れ表示、ナビ開始/終了ボタン) |
| `RouteOverlay.kt` | 経路の線(GeoJSON)を作る(`Itinerary.toOverlay()`) |
| `Navigation.kt` | ナビ(`NavigationState` / `NavigationController` / `NavigationService`)。位置を使い続け、案内の更新と通知を出す |
| `NavigationScreen.kt` | ナビ中に地図へ重ねる表示(上の案内バナー、「その後」、下の残り時間バー、✕、現在地に戻る) |
| `ui/theme/*`, `ExampleUnitTest` ほか | テンプレートの残り(未使用) |

`AndroidManifest.xml` の権限: `INTERNET`、`ACCESS_FINE_LOCATION`、`ACCESS_COARSE_LOCATION`、`POST_NOTIFICATIONS`、`FOREGROUND_SERVICE`、`FOREGROUND_SERVICE_LOCATION`。サービス `.NavigationService`(`foregroundServiceType="location"`)を宣言。

## 5. 時刻表DB(`timetable.db`)

### 作り方(PC、Python標準ライブラリのみ)

```
python3 gtfs_to_db.py NTBus-20261001.zip -o timetable.db --prefix NT_
python3 gtfs_to_db.py <JR-East-Train-GTFS フォルダ> -o timetable.db --prefix JR_ --append --operator JR東日本 ^
    --odpt-train odptTrainTimetable.json --odpt-station odptStation.json --odpt-railway odptRailway.json
```

(JRのGTFSは zip を展開したフォルダを渡す。`--append` の前のDBは同じバージョンで作る。バージョンが違うときは最初から作り直す。)

### データ元

| データ | 内容 | 有効期間 |
|---|---|---|
| 西東京バスGTFS(`NTBus-20261001.zip`) | 西東京バス・はちバス・はむらん・るのバス・ぐるりーんひのでちゃん。駅962(読み付き)、便11,962、停車時刻233,305、運賃75,488組 | 2026-10-01〜2026-12-31 |
| JR東日本GTFS(`JR-East-Train-GTFS.zip`) | 首都圏35路線、停留所711、便18,520。**運賃データなし**。読み(ひらがな)なし | 2026-03-14〜2027-03-12 |
| ODPT JSON 3種 | Station887/Railway88/TrainTimetable1,000本(ほぼ中央線快速)。**電車の種別の確定にのみ使用**(260本)。座標・駅の並びは無く未使用 | — |

### テーブル(スキーマ v5)

`meta` / `feeds` / `stations(station_id,name,lat,lon,kana,grp,kind)` / `stops(stop_id,station_id,name,platform,zone)` / `routes(route_id,operator,name,long_name,color,route_type)` / `trips(trip_no,trip_id,route_id,service_id,headsign,train_type)` / `stop_times(trip,seq,stop_id,station_id,arr_sec,dep_sec,headsign,can_board,can_alight)` / `calendar` / `calendar_dates` / `fares(route_id,from_zone,to_zone,price)`

- `grp`: 同じ名前で700m以内の駅を1グループにする(JRは路線ごとに別の停留所のため)。
- `kind`: `rail`(バス以外の便が停まる)または `bus`。地図の丸の色分けに使う。
- 運賃: 路線×乗車ゾーン×降車ゾーン。同じ組に2つの運賃があるとき(312件)は高いほうを採用。
- 電車の種別: ODPTで確定(260本)→同じ路線で停車パターンが一致する便(1,615本)→停車駅の割合から**推定**(16,645本)。推定は目安。
- 24時を過ぎる時刻(`dep_sec >= 86400`)に対応。降車専用の駅(終点のみ)も駅として残す。

## 6. 機能の進捗

凡例: ✅ ユーザーが実機で動作を確認 / 🟡 実装済み・動作の報告待ち / ⬜ 未着手

| 機能 | 状態 | 補足 |
|---|---|---|
| オフライン地図の表示(PMTiles) | ✅ | 起動時にファイルが無ければデモ地図 |
| 駅・バス停の丸と、タップで出発一覧 | ✅ | シートは画面の半分まで、スワイプで閉じない(背景タップ/戻る操作で閉じる) |
| 便の詳細(経由地と時刻、前の経由地の開閉) | ✅ | |
| 現在地マーク・起動時に現在地へ(ズーム13) | ✅ | 範囲外(東京西部以外)では移動しない |
| 「現在地に戻る」ボタン・虫眼鏡アイコン | ✅ | |
| バス経路検索(乗換・料金・3種の並べ替え) | ✅ | 徒歩300m以内の乗換、最大乗換3回、待ち90分まで、6時間先まで |
| 出発日時の指定(10/1以降なら過去も可) | 🟡 | 日付/時刻のピッカー、範囲外の警告 |
| 地図に経路の線・下の詳細パネル(縦の流れ) | 🟡 | バス=路線色の線、徒歩=青い点線。線は停留所どうしを結ぶ直線(道路に沿わない) |
| 軽いナビ(通知): 乗車5分前/1分前、降車が近い、乗換案内、到着 | 🟡 | フォアグラウンドサービス。位置とGTFSの時刻にもとづく(実際の遅れは不明)。乗車は時刻どおりと仮定 |
| ナビ画面(上の案内バナー・下の残り時間バー・進行方向が上) | 🟡 | 徒歩は「目標の停留所への直線の方向矢印+距離」(簡易版) |
| JR東日本の取り込み・駅のグループ化 | 🟡 | 検索では同名の駅を1件にまとめる。JR駅は漢字で検索(読みなし) |
| 電車の種別の表示 | 🟡 | 出発一覧・詳細パネル・通知に表示 |
| 地図の詳細化(地名・道路名・施設名・土地利用) | 🟡 | 新スタイル+字形ファイル(上記アセット)が必要。`tokyo.pmtiles` の中身(レイヤー名・属性)は未確認 |
| 地図の丸をバス停と電車で区別 | 🟡 | 電車=緑の大きい丸(ズーム10から)、バス停=赤い小さい丸(ズーム12から) |
| JRの運賃 | ⬜ | データなし。JRを含む経路は「◯円+運賃不明の区間」/「料金不明」 |
| 道路に沿った徒歩案内(曲がり角・道路名) | ⬜ | 歩行者用の道路データから経路グラフを作る必要がある。いまは直線の方向矢印のみ(C案: 簡易版→のち差し替え) |
| 全列車の種別(ODPTの全列車時刻表) | ⬜ | 現状は1,000本のみで、特別快速・通勤快速などは中央線快速以外は区別できない |
| UIを「Canon EOSシリーズの設定画面」風に | ⬜ | 機能がまとまったあとに実施。お手本の画面のスクリーンショットをもらう予定 |
| 地図の更なる詳細化(施設アイコン、より細かいデータ) | ⬜ | スプライト(アイコン画像)の追加、OSMからPMTilesを作り直す案あり |

## 7. 経路検索の仕様(`RouteSearch.kt`)

- 駅=グループ単位。出発地・目的地に選んだ駅の**グループ全体**を起点/終点にする。
- 探索: 乗る便を1本ずつ増やすラウンド方式(最大4本)。降りた駅から**300m以内**(同じグループは700mまで)の駅へ歩いて乗り換える。余裕はバス→乗車180秒、徒歩のあと60秒。乗換の待ちは90分まで。
- 出発時刻を0/5/10/15/20/30/40/50/60/75/90分ずらして複数回探し、同じ経路は除く。最速より120分以上遅い経路は出さない。
- 並べ替え: 早い順 / 乗換少ない順 / 料金安い順。同じ路線の組み合わせは1件にまとめ、上位3件。
- 料金: 区間ごとに足す(大人・データ上の運賃。IC割引・小児運賃・乗継割引は含まない)。データが無い区間を含むと、分かる分+「運賃不明の区間」。

## 8. 変更履歴(古い順)

1. 引き継ぎ・コードの照合(`PROJECT_CONTEXT.md` と実ソースの確認)。
2. 西東京バスのGTFSから時刻表DBを作成(`gtfs_to_db.py`)。`TimetableDb.kt` を追加し、駅の丸と出発一覧をDBに接続。`DemoData.kt` に `isBus`、`DepartureSheet.kt` に「行き/方面」。
3. シートのスワイプを安定化(`sheetGesturesEnabled=false`)、高さを画面の半分に制限、文字を小さく・行間を調整。
4. 便の詳細(経由地)。`Departure` に `tripNo/seq`、`TripStop` を追加。
5. GPS: 現在地マーク(MapLibre LocationComponent)、起動時に現在地へ、現在地に戻るボタン、虫眼鏡アイコン。
6. バスの経路検索: DB v2(運賃・読み・索引)、`RouteSearch.kt` / `RouteSearchScreen.kt` を追加。
7. 出発日時の指定、地図の経路の線(`RouteOverlay.kt`)、詳細パネル(`RoutePanel.kt`)。
8. ナビ(通知): `Navigation.kt`、権限とサービスの追加、出発一覧の行間。
9. ナビ画面(Googleマップ風の案内バナー・残り時間バー・進行方向追従): `NavigationScreen.kt`、`MapLibreMapView.kt` に追従と点線。徒歩は簡易版(方向矢印)。
10. JR東日本のGTFSを取り込み: DB v3(駅のグループ化、行先が空の便は終点名)、経路検索を駅グループ対応に、地図の丸は1グループ1点。バッジ文字色を明るい色で黒に。
11. 電車の種別: DB v4(`trips.train_type`)、ODPTで照合+推定、出発一覧・詳細パネル・通知に表示。
12. 地図スタイルを細かく(土地利用・水域・道路の色分け・鉄道・地名・道路名・施設名・駅名)。字形ファイルの追加が必要。
13. 地図の丸をバス停と電車で区別: DB v5(`stations.kind`)、`TimetableDb.stationsGeoJson()`、スタイルの駅レイヤーを分割、タップ判定を両レイヤーに。(今回)

## 9. 既知の制約・未確認事項

- コードは未コンパイルで渡している。赤線が出たらProblemsタブのメッセージをClaudeに伝える。
- 新スタイルの文字は、`NotoSansRegular/0-255.pbf` が無いと数字・英字が出ない。日本語は端末のフォントで描く想定(MapLibreの既定)。出ないときは `localIdeographFontFamily` の設定を追加する。
- `tokyo.pmtiles` のレイヤー名・属性(`kind` / `pmap:kind` など)は未確認。施設名・道路の色分けが出ないときは、メタデータを確認する。
- 時刻表の有効期間は、バスが12/31まで、JRが2027/3/12まで。バスの時刻は2027年に入ると出ない。
- 駅の読み(ひらがな)はバスのみ。JR駅は漢字で検索する。
- ナビの乗車判定は時刻ベース(乗り遅れの検出なし)。降車は、降りる停留所から約120m以内か到着時刻の1分後で判定。
- 電車の種別のうち、「特急」と推定した便は誤りの可能性がある。

## 10. 次にやること(候補)

1. 新スタイル・種別・丸の色分けの実機確認(出ない要素があれば原因調査)。
2. 地図のさらなる詳細化(施設アイコン用のスプライト、OSMからのPMTiles再生成)。
3. 道路に沿った徒歩案内(OSMデータから歩行者用の経路グラフ)。
4. JRの運賃(ODPTの運賃データがあれば取り込み)。全列車の種別(ODPTの全列車時刻表)。
5. UIをCanon EOS風に(スクリーンショットを受け取ってから)。
