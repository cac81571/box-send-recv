# box-send-recv

`box-automation` を、フォルダ監視によるリアルタイム送受信向けに組み替えたツールです。Edge の CDP 接続と Playwright で企業 Box を操作します。

- 監視するのはローカルフォルダだけ（サブフォルダ含む）。Box 側は監視しない
- `S_` で始まるファイルを Box へ送信する
- `R_` で始まるローカルファイルを受信処理する

## 必要環境

- Java 17 以上
- Maven 3.x
- Windows
- Edge（CDP デバッグモード）

## セットアップ

1. 設定ファイルを用意する

```powershell
copy src\main\resources\application.properties.example src\main\resources\application.properties
```

2. `application.properties` を環境に合わせて編集する
   - `box.enterprise.domain`
   - `box.folder.id` または `box.folder.url`
   - `local.watch.path`
`application.properties` は `.gitignore` 対象です。

## 起動

```bat
scripts\start-edge-cdp.bat
```

開いた Edge で企業 Box にログインしてから:

```bat
scripts\run-watch.bat
```

別の設定ファイルを使う場合:

```bat
scripts\run-watch.bat --config=C:/work/config/application.properties
```

停止は Ctrl+C です。CDP で起動した Edge は閉じません。

## ファイルの見分け方

| 接頭辞 | 向き | 動作 |
|--------|------|------|
| `S_` | 送信（ローカルのみ） | サブフォルダ構成を維持したまま Box へ上げる。`dept/S_報告.xlsx` は Box の `dept/報告.xlsx`。ローカルは同じ場所で接頭辞を外して上書き |
| `R_` | 受信 | 同じ相対パスの Box ファイルをダウンロードして上書き。`dept/R_報告.xlsx` は Box の `dept/報告.xlsx` を取得し、ローカルの `R_` は削除 |

接頭辞は `file.send.prefix` / `file.receive.prefix` で変更できます。

受信したいファイルは、監視フォルダへ同じ相対パスで `R_` 付きの名前にして置いてください。Box 上の最新版をダウンロードします。

## 処理の流れ

1. CDP で Edge に接続し、未ログインなら手動 SSO を待つ（送信時に使う）
2. ローカル監視フォルダを再帰的に走査する
3. `S_` ファイルを、監視フォルダからの相対パスどおり Box へ送信する。無いフォルダは Box 上に作る。完了後は同じ場所で接頭辞を外して上書きリネームする
4. `R_` ファイルは、同じ相対パスの Box ファイルをダウンロードして上書きし、`R_` を削除する
5. `watch.interval.seconds` 待って繰り返す

`~$` や `.tmp` などの一時ファイルは対象外です。

Box へは接頭辞を除いたファイル名で上げます。同名があれば上書きします。ローカルのフォルダ構成は維持します。

## 主な設定

| キー | 説明 |
|------|------|
| `box.enterprise.domain` | 企業 Box のホスト |
| `box.folder.url` / `box.folder.id` | 送信先フォルダ |
| `local.watch.path` | 監視ルート |
| `file.send.prefix` | 送信判定（既定 `S_`） |
| `file.receive.prefix` | 受信判定（既定 `R_`） |
| `watch.interval.seconds` | 監視間隔 |
| `browser.cdp.url` | CDP URL（必須、例: `http://localhost:9222`） |

## ログイン

初回、またはセッション切れのときは、CDP の Edge 上で SSO を手で通してください。プロファイル `edge-cdp-profile` にセッションが残れば、次回からはログイン操作なしで監視に入れます。MFA がある場合の完全無人ログインは対象外です。

## トラブルシューティング

| 症状 | 確認ポイント |
|------|----------------|
| CDP 接続失敗 | 先に `scripts\start-edge-cdp.bat` を実行しているか |
| ログイン待ちのまま | 企業 Box にその Edge でログインしたか。`box.enterprise.domain` が URL と一致するか |
| `S_` を置いても送られない | ファイル名接頭辞と拡張子フィルタを確認 |
| `R_` を受信しない | 監視フォルダ側に `R_` で置かれているか。Box 側に接頭辞なしの同名ファイルがあるか |
| 同じファイルを何度も処理する | 処理後に接頭辞が外れているか |
| PowerShell で日本語が文字化けする | `scripts\run-watch.bat` で起動する。コンソールは UTF-8（`logging.console.charset=UTF-8`） |
