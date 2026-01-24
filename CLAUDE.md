# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

This is a Discord bot written in Python that manages ARK: Survival Ascended servers running on Kubernetes. The bot provides server management, monitoring, and player information through Discord commands using RCON protocol for real server status validation.

## Essential Development Commands

### Environment Setup
```bash
# Recommended: UV package manager
uv venv && uv pip install -r requirements.txt

# Alternative: Traditional Python
python -m venv .venv && source .venv/bin/activate && pip install -r requirements.txt
```

### Testing
```bash
# Run all tests (37 test cases)
uv run pytest tests/ -v

# Run specific test file
uv run pytest tests/test_server_status_checker.py -v

# Run with coverage
uv run pytest tests/ --cov=src/ark_discord_bot

# Alternative: Traditional pytest (if venv active)
pytest tests/ -v
```

### Running the Bot
```bash
# UV (recommended)
uv run python -m src.ark_discord_bot.main

# Traditional Python
python -m src.ark_discord_bot.main
```

### Code Quality
```bash
# Format code
uv run black src/ tests/
uv run isort src/ tests/

# Lint code
uv run pylint src/ark_discord_bot/

# Or use Makefile
make format && make lint
```

### Build and Deploy
```bash
# Docker
make docker-build && make docker-run

# Kubernetes
make k8s-deploy

# Clean artifacts
make clean
```

## Architecture Overview

### Core Components
- **`ArkBotApplication`** (`main.py`): Main application orchestrator with signal handling
- **`ArkDiscordBot`** (`discord_bot.py`): Discord bot implementation with commands (`!ark help|status|restart|players`)
- **`KubernetesManager`** (`kubernetes_manager.py`): Kubernetes API operations (restart deployments, check pod status)
- **`RconManager`** (`rcon_manager.py`): RCON protocol communication with ARK server
- **`ServerStatusChecker`** (`server_status_checker.py`): Comprehensive status validation (K8s + RCON)
- **`ServerMonitor`** (`server_monitor.py`): Automated monitoring with smart state change notifications

### Server Status Flow
1. **Kubernetes Check**: Verify pods are running
2. **RCON Validation**: Test actual game server connectivity
3. **State Determination**:
   - Both successful → "🟢 Running"
   - K8s OK + RCON fail → "🟡 Starting" 
   - K8s fail → "🟡 Not Ready"
   - Error occurred → "🔴 Error"

### Configuration
All configuration via environment variables (see `.env.example`):
- Discord bot token and channel ID
- Kubernetes namespace, deployment, and service names  
- RCON connection details (host, port, password)
- Monitoring interval and log level

## Important Development Patterns

### Async/Await Throughout
All components use async/await patterns. When modifying code, maintain this pattern for consistency.

### Test-Driven Development
This project follows TDD principles with 37 comprehensive test cases. Always:
1. Run existing tests before making changes
2. Write tests for new functionality
3. Use mocking for external dependencies (K8s API, RCON, Discord)

### Code Quality and Pre-commit Checks
**IMPORTANT**: Before making any commits, ALWAYS run the following commands to ensure code quality:

```bash
# 1. Format code (required)
uv run black src/ tests/
uv run isort src/ tests/

# 2. Run linting (required) 
uv run pylint src/ark_discord_bot/

# 3. Run all tests (required)
uv run pytest tests/ -v

# Alternative: Use Makefile for all checks
make format && make lint && make test
```

**Commit Workflow:**
1. Make code changes
2. Run formatting: `uv run black src/ tests/ && uv run isort src/ tests/`
3. Check linting: `uv run pylint src/ark_discord_bot/`
4. Run tests: `uv run pytest tests/ -v`
5. Only commit if all checks pass

This ensures all commits maintain code quality and don't break existing functionality.

### Error Handling
Follow existing patterns:
- Comprehensive try/catch blocks
- Detailed logging at appropriate levels
- Graceful degradation when services unavailable

### Dependencies
- **Core**: discord.py, kubernetes, aiohttp, python-dotenv
- **Testing**: pytest, pytest-asyncio, pytest-mock
- **Code Quality**: black, isort, pylint

## Key Files to Understand

### Entry Points
- `__main__.py`: Module entry point
- `src/ark_discord_bot/main.py:109`: `main()` function
- `src/ark_discord_bot/config.py`: Configuration loading from environment

### Core Logic
- `src/ark_discord_bot/discord_bot.py:35-65`: Discord command handlers
- `src/ark_discord_bot/server_status_checker.py:15-45`: Status validation logic
- `src/ark_discord_bot/server_monitor.py:25-55`: Monitoring and notification logic

### Testing
- `tests/test_server_status_checker.py`: Critical status validation tests
- `tests/test_discord_bot_simple.py`: Discord command tests
- `tests/test_kubernetes_manager.py`: K8s operations tests

## Deployment Configurations

### Local Development
Copy `.env.example` to `.env` and configure required values.

### Docker
Uses multi-stage Dockerfile with Python 3.11-slim, non-root user (UID 10000), health checks.

### Kubernetes
Complete manifests in `k8s/` with RBAC, secrets, configmaps. Deploy with `kubectl apply -k k8s/`.

## Security Considerations
- Non-root container execution
- Minimal RBAC permissions for K8s operations
- Secrets managed via Kubernetes secrets (base64 encoded)
- No privilege escalation
- Read-only root filesystem in containers