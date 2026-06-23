# ARK Discord Bot (Clojure)

Kubernetes上で動作するARK: Survival Ascendedサーバーを管理するDiscord Bot。
RCONプロトコルを使用した実サーバーステータス検証機能を提供。

## 必要要件

- [Clojure CLI](https://clojure.org/guides/install_clojure) (clojure/clj コマンド)

## セットアップ

```bash
# 依存関係の確認
clojure --version

# テスト実行
clojure -M:test

# フォーマットチェック + lint
clojure -M:format-check && clojure -M:lint
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
clojure -M:run
```

### Docker

```bash
docker build -t ark-discord-bot:latest .
docker run --env-file .env ark-discord-bot:latest
```

## 開発コマンド

```bash
clojure -M:test          # テスト実行
clojure -M:lint          # clj-kondo linting
clojure -M:format-check  # フォーマットチェック
clojure -M:format-fix    # フォーマット修正
clojure -M:run           # ボット起動
docker build -t ark-discord-bot .  # Dockerイメージビルド
```

### GraalVM Native Image設定の自動生成

GraalVM Tracing Agentを使用して、リフレクション設定を自動生成できます。

#### Docker使用（推奨、GraalVMのローカルインストール不要）

```bash
# 初回のみ: GraalVMエージェント用Dockerイメージをビルド
make docker-native-config-build

# テスト実行時にリフレクション使用を追跡
make docker-native-config-test

# 実際のボット実行時に追跡（.envファイル必要）
# 起動後、各コマンド（!ark status等）を実行してからCtrl+Cで終了
make docker-native-config-run
```

#### ローカルGraalVM使用

ローカルにGraalVMがインストールされている場合：

```bash
# テスト実行時にリフレクション使用を追跡
make native-config-test

# 実際のボット実行時に追跡（.envファイル必要）
make native-config-run
```

#### 推奨ワークフロー

```bash
# 両方実行してカバレッジを最大化
make docker-native-config-build  # 初回のみ
make docker-native-config-test
make docker-native-config-run
```

生成された設定は `resources/META-INF/native-image/ark-discord-bot/` に保存されます。

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
clojure -M:test
```

## コード品質

- **関数サイズ制限**: 10行以下（clj-kondoカスタムフックで強制）
- **フォーマット**: cljfmt
- **Linting**: clj-kondo

## Kubernetesデプロイメント

1. `k8s/secret.yaml`でシークレットをbase64エンコードした値で更新：
```bash
echo -n "your_discord_bot_token" | base64
echo -n "123456789" | base64  # channel ID
echo -n "your_rcon_password" | base64
```

2. Kubernetesにデプロイ：
```bash
kubectl apply -k k8s/
```

## ライセンス

MIT
