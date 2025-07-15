# ARK Discord Bot - loliceクラスターデプロイメント

このディレクトリには、loliceクラスター上でARK Discord Botをデプロイするためのマニフェストが含まれています。

## 前提条件

1. **loliceクラスターへのアクセス**
   ```bash
   kubectl config use-context lolice
   kubectl cluster-info
   ```

2. **Docker イメージのビルドとプッシュ**
   ```bash
   # イメージをビルド
   docker build -t ghcr.io/boxp/ark-discord-bot:v2.0 .
   
   # GitHub Container Registryにプッシュ
   docker push ghcr.io/boxp/ark-discord-bot:v2.0
   ```

## デプロイ手順

### 1. シークレットの設定

`secret.yaml`ファイルを編集して、実際の値を設定：

```bash
# Discord Bot Token
echo -n "YOUR_ACTUAL_DISCORD_BOT_TOKEN" | base64

# Discord Channel ID  
echo -n "YOUR_ACTUAL_CHANNEL_ID" | base64

# RCON Password
echo -n "YOUR_ACTUAL_RCON_PASSWORD" | base64
```

設定後、`secret.yaml`の対応する値を置き換えてください。

### 2. 設定の確認

`configmap.yaml`でlolice環境に合わせて以下を確認/調整：

- `RCON_HOST`: ARKサーバーのRCONエンドポイント
- `KUBERNETES_NAMESPACE`: ARKサーバーがデプロイされているnamespace
- `KUBERNETES_DEPLOYMENT_NAME`: ARKサーバーのdeployment名
- `KUBERNETES_SERVICE_NAME`: ARKサーバーのservice名

### 3. デプロイ実行

```bash
# loliceクラスターでデプロイ
kubectl apply -k k8s/lolice/

# デプロイ状況の確認
kubectl get pods -n ark-discord-bot
kubectl logs -f deployment/ark-discord-bot -n ark-discord-bot
```

### 4. 動作確認

1. **Pod の状態確認**
   ```bash
   kubectl get pods -n ark-discord-bot
   kubectl describe pod -l app.kubernetes.io/name=ark-discord-bot -n ark-discord-bot
   ```

2. **ログの確認**
   ```bash
   kubectl logs -f deployment/ark-discord-bot -n ark-discord-bot
   ```

3. **Discord での動作確認**
   - `!ark help` - ヘルプコマンドの動作確認
   - `!ark status` - サーバーステータスの確認

## トラブルシューティング

### Pod が起動しない場合

1. **イメージの確認**
   ```bash
   kubectl describe pod -l app.kubernetes.io/name=ark-discord-bot -n ark-discord-bot
   ```

2. **RBAC権限の確認**
   ```bash
   kubectl auth can-i get deployments --as=system:serviceaccount:ark-discord-bot:ark-discord-bot -n ark-survival-ascended
   kubectl auth can-i patch deployments --as=system:serviceaccount:ark-discord-bot:ark-discord-bot -n ark-survival-ascended
   ```

3. **設定の確認**
   ```bash
   kubectl get configmap ark-discord-bot-config -n ark-discord-bot -o yaml
   kubectl get secret ark-discord-bot-secret -n ark-discord-bot -o yaml
   ```

### ARKサーバーへの接続が失敗する場合

1. **ネットワーク接続の確認**
   ```bash
   kubectl exec -it deployment/ark-discord-bot -n ark-discord-bot -- ping ark-server-service.ark-survival-ascended.svc.cluster.local
   ```

2. **RCON接続のテスト**
   ```bash
   kubectl exec -it deployment/ark-discord-bot -n ark-discord-bot -- python -c "
   import asyncio
   from src.ark_discord_bot.rcon_manager import RconManager
   import os
   
   async def test():
       rcon = RconManager(
           host=os.getenv('RCON_HOST'),
           port=int(os.getenv('RCON_PORT')),
           password=os.getenv('RCON_PASSWORD')
       )
       result = await rcon.send_command('echo test')
       print(f'RCON test result: {result}')
   
   asyncio.run(test())
   "
   ```

## リソース使用量

- **メモリ**: 256Mi リクエスト, 1Gi 制限
- **CPU**: 200m リクエスト, 1000m 制限

本番環境での使用量に応じて調整してください。

## セキュリティ

- 非rootユーザー（UID 10000）で実行
- 読み取り専用ルートファイルシステム
- 最小権限のRBAC設定
- ARKサーバーのdeploymentとserviceのみアクセス可能

## 更新手順

1. **新しいイメージのビルド**
   ```bash
   docker build -t ghcr.io/boxp/ark-discord-bot:v2.1 .
   docker push ghcr.io/boxp/ark-discord-bot:v2.1
   ```

2. **kustomization.yamlの更新**
   ```yaml
   images:
   - name: ghcr.io/boxp/ark-discord-bot
     newTag: v2.1
   ```

3. **デプロイの更新**
   ```bash
   kubectl apply -k k8s/lolice/
   kubectl rollout status deployment/ark-discord-bot -n ark-discord-bot
   ```