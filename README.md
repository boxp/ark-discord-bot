# ARK Discord Bot

Kubernetesで稼働するARK: Survival Ascendedサーバーを管理するためのDiscord botです。Discordコマンドによるサーバー管理機能とRCONベースの接続確認による自動サーバーステータス通知を提供します。

## 機能

🔄 **サーバー管理**
- Kubernetesのrollout restartを使用したARKサーバーの再起動
- RCON接続確認による包括的なサーバーステータスチェック
- リアルタイムでのサーバー準備状態検出

👥 **プレイヤー管理** 
- RCONを介した現在オンラインのプレイヤー一覧表示
- リアルタイムプレイヤー情報

🔔 **スマート通知**
- サーバーが接続可能状態になった際の自動通知
- ポッドの起動と実際のゲームサーバー準備状態の区別
- 詳細なステータス情報によるサーバー状態変更アラート
- エラー通知

❓ **ヘルプシステム**
- 使用例付きの対話型ヘルプコマンド

## Discordコマンド

- `!ark help` - ヘルプ情報を表示
- `!ark status` - 包括的なサーバーステータスをチェック（K8s + RCON接続性）
- `!ark restart` - ARKサーバーを再起動
- `!ark players` - 現在オンラインのプレイヤー一覧を表示

### サーバーステータス状態

`!ark status`コマンドは詳細なサーバー状態情報を提供します：

- 🟢 **Running**: サーバーが準備完了で接続を受け付けています（RCON接続可能）
- 🟡 **Starting**: Kubernetesポッドは稼働中ですが、ゲームサーバーはまだ初期化中
- 🟡 **Not Ready**: Kubernetesデプロイメントが準備未完了
- 🔴 **Error**: サーバーでエラーが発生

## アーキテクチャ

このプロジェクトはテスト駆動開発（TDD）の原則に従い、以下を使用しています：

- **Kubernetes API** - サーバー再起動とポッドステータス監視
- **RCONプロトコル** - サーバー接続確認とプレイヤー情報取得
- **Discord.py** - Discord bot機能
- **AsyncIO** - 並行処理
- **uv** - 高速Pythonパッケージ管理と仮想環境
- **Pytest** - 包括的テスト（37テストケース）

### 主要コンポーネント

- `KubernetesManager` - Kubernetes操作を処理
- `RconManager` - ARKサーバーとのRCON通信を管理
- `ServerStatusChecker` - K8s + RCONを使用した包括的サーバーステータス検証
- `ServerMonitor` - スマート通知でサーバーステータス変更を監視
- `ArkDiscordBot` - メインのDiscord bot実装

## セットアップ手順

### 前提条件

- Python 3.10+（3.11+推奨）
- [uv](https://docs.astral.sh/uv/) - Pythonパッケージマネージャー
- ARKサーバーがデプロイされたKubernetesクラスター
- Discord botトークン
- ARKサーバーへのRCONアクセス

### 環境設定

1. `.env.example`を`.env`にコピー：
```bash
cp .env.example .env
```

2. `.env`で設定を構成：
```env
DISCORD_BOT_TOKEN=your_discord_bot_token_here
DISCORD_CHANNEL_ID=your_discord_channel_id_here
KUBERNETES_NAMESPACE=ark-survival-ascended
KUBERNETES_DEPLOYMENT_NAME=ark-server
KUBERNETES_SERVICE_NAME=ark-server-service
RCON_HOST=192.168.10.29
RCON_PORT=27020
RCON_PASSWORD=your_rcon_password_here
```

### ローカル開発

1. uvがインストールされていない場合はインストール：
```bash
curl -LsSf https://astral.sh/uv/install.sh | sh
```

2. 仮想環境を作成し依存関係をインストール：
```bash
uv venv
uv pip install -r requirements.txt
```

3. テストを実行：
```bash
uv run pytest tests/ -v
```

4. botを実行：
```bash
uv run python -m src.ark_discord_bot.main
```

### 代替案：従来のPythonセットアップ

従来のPythonツールを使用したい場合：

1. 仮想環境を作成：
```bash
python -m venv .venv
source .venv/bin/activate  # Windows: .venv\Scripts\activate
```

2. 依存関係をインストール：
```bash
pip install -r requirements.txt
```

3. テストを実行：
```bash
pytest tests/ -v
```

### Dockerデプロイメント

1. イメージをビルド：
```bash
docker build -t ark-discord-bot:latest .
```

2. Docker Composeで実行：
```bash
docker-compose up -d
```

### Kubernetesデプロイメント

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

## 開発

### プロジェクト構造

```
ark-discord-bot/
├── src/ark_discord_bot/          # メインアプリケーションコード
│   ├── __init__.py
│   ├── main.py                   # アプリケーションエントリーポイント
│   ├── config.py                 # 設定管理
│   ├── discord_bot.py            # Discord bot実装
│   ├── kubernetes_manager.py     # Kubernetes操作
│   ├── rcon_manager.py          # RCON通信
│   ├── server_status_checker.py # RCONベースサーバーステータス検証
│   └── server_monitor.py        # サーバーステータス監視
├── tests/                        # テストファイル（TDDアプローチ - 37テスト）
│   ├── test_discord_bot_simple.py     # Discord botテスト
│   ├── test_kubernetes_manager.py     # Kubernetes操作テスト
│   ├── test_rcon_manager.py          # RCON通信テスト
│   ├── test_server_status_checker.py # サーバーステータス検証テスト
│   └── test_server_monitor.py        # サーバー監視テスト
├── k8s/                          # Kubernetesマニフェスト
│   ├── namespace.yaml
│   ├── configmap.yaml
│   ├── secret.yaml
│   ├── rbac.yaml
│   ├── deployment.yaml
│   └── kustomization.yaml
├── requirements.txt              # Python依存関係
├── uv.lock                      # uv再現可能インストール用ロックファイル
├── pyproject.toml               # プロジェクト設定
├── Dockerfile                   # Dockerイメージ
├── docker-compose.yml           # Docker Composeセットアップ
├── Makefile                     # 開発コマンド
└── README.md                    # このファイル
```

### テスト

プロジェクトは包括的なテストカバレッジでpytestを使用（37テストケース）：

```bash
# uvですべてのテストを実行
uv run pytest tests/ -v

# 特定のテストファイルを実行
uv run pytest tests/test_server_status_checker.py -v

# カバレッジ付きで実行
uv run pytest tests/ --cov=src/ark_discord_bot

# 代替案：従来のpytest（仮想環境が有効化されている場合）
pytest tests/ -v
```

#### テストカバレッジ

- **Discord Botテスト**: コマンド処理とレスポンス検証
- **Kubernetes Managerテスト**: デプロイメント再起動とステータスチェック
- **RCON Managerテスト**: プロトコル通信とプレイヤー一覧
- **Server Status Checkerテスト**: RCON接続確認検証
- **Server Monitorテスト**: ステータス変更通知と監視ロジック

### 開発コマンド

#### uvを使用（推奨）

```bash
# 開発環境のセットアップ
uv venv && uv pip install -r requirements.txt

# テストを実行
uv run pytest tests/ -v

# コードをフォーマット
uv run black src/ tests/
uv run isort src/ tests/

# コードをリント
uv run pylint src/ark_discord_bot/

# botを実行
uv run python -m src.ark_discord_bot.main
```

#### Makefileを使用

共通の開発タスクにMakefileを使用：

```bash
make help          # 利用可能なコマンドを表示
make install       # 依存関係をインストール
make test          # テストを実行
make docker-build  # Dockerイメージをビルド
make k8s-deploy    # Kubernetesにデプロイ
```

## セキュリティ

- 非rootユーザーとして実行（UID 10000）
- 読み取り専用ルートファイルシステム
- 最小限のRBAC権限
- Kubernetesシークレットでシークレット管理
- 権限昇格なし

## 監視

botには以下が含まれています：
- DockerとKubernetes用ヘルスチェック
- 包括的ログ記録
- サーバーステータス監視
- エラー処理と復旧

## トラブルシューティング

### よくある問題

1. **botがコマンドに応答しない**
   - Discord botトークンを確認
   - botがDiscordサーバーで適切な権限を持っているか確認
   - 接続エラーのログを確認

2. **Kubernetes操作が失敗する**
   - RBAC権限を確認
   - botがark-survival-ascendedネームスペースにアクセスできるか確認
   - デプロイメント名が設定と一致するか確認

3. **RCON接続の問題**
   - RCONホストとポートを確認
   - RCONパスワードを確認
   - ARKサーバーでRCONが有効になっているか確認
   - RCON接続を手動でテスト

4. **サーバーステータスが長時間"starting"を表示**
   - ARKサーバーが実際に準備完了しているか確認（ポッド開始後5-10分かかる場合があります）
   - サーバーへのRCON接続を確認
   - サーバー初期化ログを確認

5. **サーバー監視が動作しない**
   - Kubernetes API接続を確認
   - ネームスペースとデプロイメント名を確認
   - 監視間隔設定を確認
   - ServerStatusCheckerが正しく動作しているか確認

### ログ

アプリケーションログの確認：
```bash
# Docker
docker logs ark-discord-bot

# Kubernetes  
kubectl logs -f deployment/ark-discord-bot -n ark-discord-bot

# ローカル開発
tail -f ark_discord_bot.log
```

## 最新の更新

### v2.0 - RCONベースサーバーステータス検証

- **強化されたサーバーステータスチェック**: 実際のサーバー準備状態を確認するためにRCON接続を使用
- **スマートステート検出**: ポッドの起動とゲームサーバーの初期化を区別
- **改善された通知**: より正確なサーバー準備通知
- **包括的テスト**: すべてのシナリオをカバーする37テストケース
- **uv統合**: 高速依存関係管理と仮想環境処理

### サーバーステータスフロー

1. **Kubernetesチェック**: ポッドが稼働中か確認
2. **RCON検証**: 実際のゲームサーバー接続をテスト
3. **ステート判定**: 
   - 両方とも成功 → "Running" 
   - K8sは成功だがRCONが失敗 → "Starting"
   - K8sが失敗 → "Not Ready"
   - エラーが発生 → "Error"

## 貢献

1. TDDの原則に従う - テストを最初に書く
2. 依存関係管理にuvを使用: `uv run pytest tests/ -v`
3. PRを提出する前に37のテストすべてが成功することを確認
4. 意味のあるコミットメッセージを使用
5. 必要に応じてドキュメントを更新

## ライセンス

このプロジェクトはMITライセンスの下でライセンスされています。