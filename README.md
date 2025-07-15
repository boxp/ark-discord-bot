# ARK Discord Bot

A Discord bot for managing ARK: Survival Ascended server running in Kubernetes. This bot provides server management capabilities through Discord commands and automated server status notifications.

## Features

🔄 **Server Management**
- Restart ARK server using Kubernetes rollout restart
- Check server status (running/not ready/error)

👥 **Player Management** 
- List current online players via RCON
- Real-time player information

🔔 **Notifications**
- Automatic notifications when server becomes ready
- Server status change alerts
- Error notifications

❓ **Help System**
- Interactive help command with usage examples

## Discord Commands

- `!ark help` - Display help information
- `!ark status` - Check current server status  
- `!ark restart` - Restart the ARK server
- `!ark players` - List current online players

## Architecture

This project follows Test-Driven Development (TDD) principles and uses:

- **Kubernetes API** - For server restart and status monitoring
- **RCON Protocol** - For player information and server commands
- **Discord.py** - For Discord bot functionality
- **AsyncIO** - For concurrent operations
- **Pytest** - For comprehensive testing

### Key Components

- `KubernetesManager` - Handles Kubernetes operations
- `RconManager` - Manages RCON communication with ARK server
- `ServerMonitor` - Monitors server status changes
- `ArkDiscordBot` - Main Discord bot implementation

## Setup Instructions

### Prerequisites

- Python 3.11+
- Kubernetes cluster with ARK server deployed
- Discord bot token
- RCON access to ARK server

### Environment Configuration

1. Copy `.env.example` to `.env`:
```bash
cp .env.example .env
```

2. Configure your settings in `.env`:
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

### Local Development

1. Install dependencies:
```bash
pip install -r requirements.txt
```

2. Run tests:
```bash
pytest tests/ -v
```

3. Run the bot:
```bash
python -m src.ark_discord_bot.main
```

### Docker Deployment

1. Build image:
```bash
docker build -t ark-discord-bot:latest .
```

2. Run with Docker Compose:
```bash
docker-compose up -d
```

### Kubernetes Deployment

1. Update secrets in `k8s/secret.yaml` with base64 encoded values:
```bash
echo -n "your_discord_bot_token" | base64
echo -n "123456789" | base64  # channel ID
echo -n "your_rcon_password" | base64
```

2. Deploy to Kubernetes:
```bash
kubectl apply -k k8s/
```

## Development

### Project Structure

```
ark-discord-bot/
├── src/ark_discord_bot/          # Main application code
│   ├── __init__.py
│   ├── main.py                   # Application entry point
│   ├── config.py                 # Configuration management
│   ├── discord_bot.py            # Discord bot implementation
│   ├── kubernetes_manager.py     # Kubernetes operations
│   ├── rcon_manager.py          # RCON communication
│   └── server_monitor.py        # Server status monitoring
├── tests/                        # Test files (TDD approach)
│   ├── test_discord_bot.py
│   ├── test_kubernetes_manager.py
│   ├── test_rcon_manager.py
│   └── test_server_monitor.py
├── k8s/                          # Kubernetes manifests
│   ├── namespace.yaml
│   ├── configmap.yaml
│   ├── secret.yaml
│   ├── rbac.yaml
│   ├── deployment.yaml
│   └── kustomization.yaml
├── requirements.txt              # Python dependencies
├── pyproject.toml               # Project configuration
├── Dockerfile                   # Docker image
├── docker-compose.yml           # Docker Compose setup
├── Makefile                     # Development commands
└── README.md                    # This file
```

### Testing

The project uses pytest with comprehensive test coverage:

```bash
# Run all tests
pytest tests/ -v

# Run specific test file
pytest tests/test_discord_bot.py -v

# Run with coverage
pytest tests/ --cov=src/ark_discord_bot
```

### Development Commands

Use the Makefile for common development tasks:

```bash
make help          # Show available commands
make install       # Install dependencies
make test          # Run tests
make docker-build  # Build Docker image
make k8s-deploy    # Deploy to Kubernetes
```

## Security

- Runs as non-root user (UID 10000)
- Read-only root filesystem
- Minimal RBAC permissions
- Secrets managed via Kubernetes secrets
- No privilege escalation

## Monitoring

The bot includes:
- Health checks for Docker and Kubernetes
- Comprehensive logging
- Server status monitoring
- Error handling and recovery

## Troubleshooting

### Common Issues

1. **Bot not responding to commands**
   - Check Discord bot token
   - Verify bot has proper permissions in Discord server
   - Check logs for connection errors

2. **Kubernetes operations failing**
   - Verify RBAC permissions
   - Check if bot can access the ark-survival-ascended namespace
   - Ensure deployment name matches configuration

3. **RCON connection issues**
   - Verify RCON host and port
   - Check RCON password
   - Ensure ARK server has RCON enabled

4. **Server monitoring not working**
   - Check Kubernetes API connectivity
   - Verify namespace and deployment names
   - Review monitoring interval settings

### Logs

Check application logs:
```bash
# Docker
docker logs ark-discord-bot

# Kubernetes  
kubectl logs -f deployment/ark-discord-bot -n ark-discord-bot

# Local development
tail -f ark_discord_bot.log
```

## Contributing

1. Follow TDD principles - write tests first
2. Ensure all tests pass before submitting PR
3. Use meaningful commit messages
4. Update documentation as needed

## License

This project is licensed under the MIT License.