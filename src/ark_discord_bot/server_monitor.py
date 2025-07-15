"""Server monitoring for ARK server status changes."""

import asyncio
import logging
from typing import Optional

logger = logging.getLogger(__name__)


class ServerMonitor:
    """Monitors ARK server status and sends notifications."""

    def __init__(self, kubernetes_manager, discord_bot, channel_id: int, check_interval: int = 30):
        """Initialize ServerMonitor.
        
        Args:
            kubernetes_manager: KubernetesManager instance
            discord_bot: Discord bot instance
            channel_id: Discord channel ID for notifications
            check_interval: Status check interval in seconds
        """
        self.kubernetes_manager = kubernetes_manager
        self.discord_bot = discord_bot
        self.channel_id = channel_id
        self.check_interval = check_interval
        self.last_status: Optional[str] = None
        self.is_monitoring = False

    async def start_monitoring(self):
        """Start monitoring server status."""
        self.is_monitoring = True
        logger.info("Starting server status monitoring")
        
        while self.is_monitoring:
            try:
                await self._check_server_status()
                await asyncio.sleep(self.check_interval)
            except asyncio.CancelledError:
                break
            except Exception as e:
                logger.error(f"Error in monitoring loop: {e}")
                await asyncio.sleep(self.check_interval)
        
        logger.info("Server status monitoring stopped")

    def stop_monitoring(self):
        """Stop monitoring server status."""
        self.is_monitoring = False

    async def get_current_status(self) -> str:
        """Get current server status.
        
        Returns:
            str: Current server status
        """
        return await self.kubernetes_manager.get_server_status()

    async def _check_server_status(self):
        """Check server status and send notifications if changed."""
        try:
            current_status = await self.kubernetes_manager.get_server_status()
            
            if self.last_status != current_status:
                await self._send_status_notification(current_status, self.last_status)
                self.last_status = current_status
                
        except Exception as e:
            logger.error(f"Error checking server status: {e}")

    async def _send_status_notification(self, current_status: str, previous_status: Optional[str]):
        """Send notification about status change.
        
        Args:
            current_status: Current server status
            previous_status: Previous server status
        """
        try:
            message = None
            
            if current_status == "running" and previous_status != "running":
                message = "🟢 ARK Server is now ready for connections! 🦕"
            elif current_status == "not_ready" and previous_status == "running":
                message = "🟡 ARK Server is restarting or not ready..."
            elif current_status == "error":
                message = "🔴 ARK Server encountered an error! Please check the logs."
            
            if message:
                await self.discord_bot.send_message(self.channel_id, message)
                logger.info(f"Sent status notification: {message}")
                
        except Exception as e:
            logger.error(f"Error sending status notification: {e}")