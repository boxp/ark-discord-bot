# ARK Discord Bot (Clojure/Babashka)

Kubernetes上で動作するARK: Survival Ascendedサーバーを管理するDiscord Bot。
RCONプロトコルを使用した実サーバーステータス検証機能を提供。

## 必要要件

- [Babashka](https://babashka.org/) (v1.3.0以上)
- [clj-kondo](https://github.com/clj-kondo/clj-kondo) (lint用)
- [cljfmt](https://github.com/weavejester/cljfmt) (format用)

## セットアップ

```bash
cd clj

# 依存関係の確認
bb --version

# テスト実行
bb test

# 全チェック（format + lint + test）
bb ci
```

## 実行方法

### 環境変数

`.env`ファイルを作成（または環境変数を設定）:

```bash
# Discord設定
DISCORD_TOKEN=your_bot_token
DISCORD_CHANNEL_ID=your_channel_id

# Kubernetes設定
K8S_NAMESPACE=default
K8S_DEPLOYMENT_NAME=ark-server
K8S_SERVICE_NAME=ark-service

# RCON設定
RCON_HOST=localhost
RCON_PORT=27020
RCON_PASSWORD=your_rcon_password
RCON_TIMEOUT=10000

# 監視設定
MONITOR_INTERVAL=60000
FAILURE_THRESHOLD=3
LOG_LEVEL=INFO
```

### ボット起動

```bash
bb start
```

### Docker

```bash
bb docker:build
docker run --env-file ../.env ark-discord-bot-clj:latest
```

## 開発コマンド

```bash
bb test           # テスト実行
bb lint           # clj-kondo linting
bb format:check   # フォーマットチェック
bb format:fix     # フォーマット修正
bb ci             # 全チェック（CI用）
bb start          # ボット起動
bb docker:build   # Dockerイメージビルド
```

## Discordコマンド

| コマンド | 説明 |
|---------|------|
| `!ark help` | ヘルプメッセージを表示 |
| `!ark status` | サーバーステータスを確認 |
| `!ark players` | オンラインプレイヤー一覧を表示 |
| `!ark restart` | サーバーを再起動（確認ダイアログ付き） |

## アーキテクチャ

```
src/ark_discord_bot/
├── core.clj              # メインエントリポイント
├── config.clj            # 設定管理
├── discord/
│   ├── client.clj        # Discord HTTP API
│   ├── commands.clj      # コマンドハンドラ
│   └── gateway.clj       # Discord WebSocket Gateway
├── kubernetes/
│   └── client.clj        # K8s API操作
├── rcon/
│   ├── protocol.clj      # RCONバイナリプロトコル
│   └── client.clj        # RCONクライアント
└── server/
    ├── status_checker.clj # 2段階ステータス検証
    └── monitor.clj       # 自動監視（デバウンス付き）
```

### サーバーステータスフロー

1. **Kubernetes確認**: ポッドが稼働中か確認
2. **RCON検証**: ゲームサーバーへの実接続テスト
3. **ステータス判定**:
   - 両方成功 → 🟢 稼働中
   - K8s OK + RCON失敗 → 🟡 起動中
   - K8s失敗 → 🟡 準備未完了
   - エラー発生 → 🔴 エラー

## テスト

```bash
# 全テスト実行
bb test

# テストカバレッジ: 60テストケース
```

## コード品質

- **関数サイズ制限**: 10行以下（clj-kondoカスタムフックで強制）
- **フォーマット**: cljfmt
- **Linting**: clj-kondo

## ライセンス

このプロジェクトはプライベートリポジトリです。
