# ARK Discord Bot

A Discord bot for managing ARK: Survival Ascended server running in Kubernetes. This bot provides server management capabilities through Discord commands and automated server status notifications with RCON-based connectivity verification.

## Features

🔄 **Server Management**
- Restart ARK server using Kubernetes rollout restart
- Comprehensive server status checking with RCON connectivity validation
- Real-time server readiness detection

👥 **Player Management** 
- List current online players via RCON
- Real-time player information

🔔 **Smart Notifications**
- Automatic notifications when server becomes ready for connections
- Differentiate between pod startup and actual game server readiness
- Server status change alerts with detailed state information
- Error notifications

❓ **Help System**
- Interactive help command with usage examples

## Discord Commands

- `!ark help` - Display help information
- `!ark status` - Check comprehensive server status (K8s + RCON connectivity)
- `!ark restart` - Restart the ARK server
- `!ark players` - List current online players

### Server Status States

The `!ark status` command now provides detailed server state information:

- 🟢 **Running**: Server is ready and accepting connections (RCON accessible)
- 🟡 **Starting**: Kubernetes pods are running but game server is still initializing
- 🟡 **Not Ready**: Kubernetes deployment is not ready
- 🔴 **Error**: Server encountered an error

## Architecture

This project follows Test-Driven Development (TDD) principles and uses:

- **Kubernetes API** - For server restart and pod status monitoring
- **RCON Protocol** - For server connectivity validation and player information
- **Discord.py** - For Discord bot functionality
- **AsyncIO** - For concurrent operations
- **uv** - For fast Python package management and virtual environments
- **Pytest** - For comprehensive testing (37 test cases)

### Key Components

- `KubernetesManager` - Handles Kubernetes operations
- `RconManager` - Manages RCON communication with ARK server
- `ServerStatusChecker` - Comprehensive server status validation using K8s + RCON
- `ServerMonitor` - Monitors server status changes with smart notifications
- `ArkDiscordBot` - Main Discord bot implementation

## Setup Instructions

### Prerequisites

- Python 3.10+ (3.11+ recommended)
- [uv](https://docs.astral.sh/uv/) - Python package manager
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

1. Install uv if not already installed:
```bash
curl -LsSf https://astral.sh/uv/install.sh | sh
```

2. Create virtual environment and install dependencies:
```bash
uv venv
uv pip install -r requirements.txt
```

3. Run tests:
```bash
uv run pytest tests/ -v
```

4. Run the bot:
```bash
uv run python -m src.ark_discord_bot.main
```

### Alternative: Traditional Python Setup

If you prefer using traditional Python tools:

1. Create virtual environment:
```bash
python -m venv .venv
source .venv/bin/activate  # On Windows: .venv\Scripts\activate
```

2. Install dependencies:
```bash
pip install -r requirements.txt
```

3. Run tests:
```bash
pytest tests/ -v
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
│   ├── server_status_checker.py # RCON-based server status validation
│   └── server_monitor.py        # Server status monitoring
├── tests/                        # Test files (TDD approach - 37 tests)
│   ├── test_discord_bot_simple.py     # Discord bot tests
│   ├── test_kubernetes_manager.py     # Kubernetes operations tests
│   ├── test_rcon_manager.py          # RCON communication tests
│   ├── test_server_status_checker.py # Server status validation tests
│   └── test_server_monitor.py        # Server monitoring tests
├── k8s/                          # Kubernetes manifests
│   ├── namespace.yaml
│   ├── configmap.yaml
│   ├── secret.yaml
│   ├── rbac.yaml
│   ├── deployment.yaml
│   └── kustomization.yaml
├── requirements.txt              # Python dependencies
├── uv.lock                      # uv lockfile for reproducible installs
├── pyproject.toml               # Project configuration
├── Dockerfile                   # Docker image
├── docker-compose.yml           # Docker Compose setup
├── Makefile                     # Development commands
└── README.md                    # This file
```

### Testing

The project uses pytest with comprehensive test coverage (37 test cases):

```bash
# Run all tests with uv
uv run pytest tests/ -v

# Run specific test file
uv run pytest tests/test_server_status_checker.py -v

# Run with coverage
uv run pytest tests/ --cov=src/ark_discord_bot

# Alternative: Traditional pytest (if virtual environment is activated)
pytest tests/ -v
```

#### Test Coverage

- **Discord Bot Tests**: Command handling and response validation
- **Kubernetes Manager Tests**: Deployment restart and status checking
- **RCON Manager Tests**: Protocol communication and player listing
- **Server Status Checker Tests**: RCON connectivity validation
- **Server Monitor Tests**: Status change notifications and monitoring logic

### Development Commands

#### Using uv (Recommended)

```bash
# Setup development environment
uv venv && uv pip install -r requirements.txt

# Run tests
uv run pytest tests/ -v

# Format code
uv run black src/ tests/
uv run isort src/ tests/

# Lint code
uv run pylint src/ark_discord_bot/

# Run the bot
uv run python -m src.ark_discord_bot.main
```

#### Using Makefile

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
   - Test RCON connectivity manually

4. **Server status showing "starting" for too long**
   - Check if ARK server is actually ready (may take 5-10 minutes after pod start)
   - Verify RCON connectivity to the server
   - Check server initialization logs

5. **Server monitoring not working**
   - Check Kubernetes API connectivity
   - Verify namespace and deployment names
   - Review monitoring interval settings
   - Ensure ServerStatusChecker is working correctly

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

## Recent Updates

### v2.0 - RCON-Based Server Status Validation

- **Enhanced Server Status Checking**: Now uses RCON connectivity to verify actual server readiness
- **Smart State Detection**: Distinguishes between pod startup and game server initialization
- **Improved Notifications**: More accurate server ready notifications
- **Comprehensive Testing**: 37 test cases covering all scenarios
- **uv Integration**: Fast dependency management and virtual environment handling

### Server Status Flow

1. **Kubernetes Check**: Verify pod is running
2. **RCON Validation**: Test actual game server connectivity
3. **State Determination**: 
   - If both pass → "Running" 
   - If K8s passes but RCON fails → "Starting"
   - If K8s fails → "Not Ready"
   - If errors occur → "Error"

## Contributing

1. Follow TDD principles - write tests first
2. Use uv for dependency management: `uv run pytest tests/ -v`
3. Ensure all 37 tests pass before submitting PR
4. Use meaningful commit messages
5. Update documentation as needed

## License

This project is licensed under the MIT License.