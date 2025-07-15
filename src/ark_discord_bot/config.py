"""Configuration management for ARK Discord Bot."""

import os
from typing import Any, Dict

from dotenv import load_dotenv

# Load environment variables from .env file
load_dotenv()


def get_config() -> Dict[str, Any]:
    """Get configuration from environment variables.

    Returns:
        Dict[str, Any]: Configuration dictionary
    """
    config = {
        "discord_token": os.getenv("DISCORD_BOT_TOKEN"),
        "channel_id": int(os.getenv("DISCORD_CHANNEL_ID", "0")),
        "kubernetes_namespace": os.getenv(
            "KUBERNETES_NAMESPACE", "ark-survival-ascended"
        ),
        "kubernetes_deployment_name": os.getenv(
            "KUBERNETES_DEPLOYMENT_NAME", "ark-server"
        ),
        "kubernetes_service_name": os.getenv(
            "KUBERNETES_SERVICE_NAME", "ark-server-service"
        ),
        "rcon_host": os.getenv("RCON_HOST", "192.168.10.29"),
        "rcon_port": int(os.getenv("RCON_PORT", "27020")),
        "rcon_password": os.getenv("RCON_PASSWORD"),
        "monitoring_interval": int(os.getenv("MONITORING_INTERVAL", "30")),
        "log_level": os.getenv("LOG_LEVEL", "INFO"),
    }

    # Validate required configuration
    required_keys = ["discord_token", "channel_id", "rcon_password"]
    for key in required_keys:
        if not config.get(key):
            raise ValueError(f"Missing required configuration: {key}")

    return config
