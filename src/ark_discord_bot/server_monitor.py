"""Server monitoring for ARK server status changes."""

import asyncio
import logging
from dataclasses import dataclass
from typing import Optional

logger = logging.getLogger(__name__)


@dataclass
class MonitorConfig:
    """Configuration for ServerMonitor."""

    channel_id: int
    check_interval: int = 30
    failure_threshold: int = 1


class ServerMonitor:
    """Monitors ARK server status and sends notifications."""

    def __init__(
        self,
        server_status_checker,
        discord_bot,
        config: MonitorConfig,
    ):
        """Initialize ServerMonitor.

        Args:
            server_status_checker: ServerStatusChecker instance
            discord_bot: Discord bot instance
            config: Monitor configuration
        """
        self.server_status_checker = server_status_checker
        self.discord_bot = discord_bot
        self.config = config
        self.last_status: Optional[str] = None
        self.is_monitoring = False
        self._failure_count: int = 0

    async def start_monitoring(self):
        """Start monitoring server status."""
        self.is_monitoring = True
        logger.info("Starting server status monitoring")

        while self.is_monitoring:
            try:
                await self._check_server_status()
                await asyncio.sleep(self.config.check_interval)
            except asyncio.CancelledError:
                break
            except Exception as e:
                logger.error(f"Error in monitoring loop: {e}")
                await asyncio.sleep(self.config.check_interval)

        logger.info("Server status monitoring stopped")

    def stop_monitoring(self):
        """Stop monitoring server status."""
        self.is_monitoring = False

    async def get_current_status(self) -> str:
        """Get current server status.

        Returns:
            str: Current server status
        """
        return await self.server_status_checker.get_server_status()

    async def _check_server_status(self):
        """Check server status and send notifications if changed."""
        try:
            current_status = await self.server_status_checker.get_server_status()

            # Skip notification and status update for transient errors
            # Reset failure count to break consecutive failure streak
            if current_status == "transient_error":
                if self._failure_count > 0:
                    logger.debug(
                        f"Transient error detected, resetting failure count from {self._failure_count}"
                    )
                    self._failure_count = 0
                else:
                    logger.debug(
                        "Transient error detected, maintaining last status and skipping notification"
                    )
                return

            # Handle debounce logic for degraded states (starting, not_ready)
            if self.last_status == "running" and current_status in [
                "starting",
                "not_ready",
            ]:
                self._failure_count += 1
                logger.debug(
                    f"Failure count: {self._failure_count}/{self.config.failure_threshold}"
                )

                if self._failure_count < self.config.failure_threshold:
                    # Not enough consecutive failures, don't update status or notify
                    return

                # Threshold reached, reset counter and proceed with notification
                self._failure_count = 0

            # Recovery to running state - reset failure counter
            if current_status == "running":
                if self._failure_count > 0:
                    # Was counting failures but recovered before threshold
                    logger.debug(
                        f"Recovery detected, resetting failure count from {self._failure_count}"
                    )
                    self._failure_count = 0
                    # Don't send notification since we never reached degraded state
                    return

            # Error state - no debounce, immediate notification
            if current_status == "error":
                self._failure_count = 0

            if self.last_status != current_status:
                await self._send_status_notification(current_status, self.last_status)
                self.last_status = current_status

        except Exception as e:
            logger.error(f"Error checking server status: {e}")

    async def _send_status_notification(
        self, current_status: str, previous_status: Optional[str]
    ):
        """Send notification about status change.

        Args:
            current_status: Current server status
            previous_status: Previous server status
        """
        try:
            message = None

            if current_status == "running" and previous_status != "running":
                message = "🟢 ARKサーバーが接続準備完了しました！ 🦕"
            elif current_status == "starting" and previous_status == "not_ready":
                message = "🟡 ARKサーバーポッドが稼働中、ゲームサーバー起動中..."
            elif (
                current_status in ["not_ready", "starting"]
                and previous_status == "running"
            ):
                message = "🟡 ARKサーバーが再起動中または準備未完了です..."
            elif current_status == "error":
                message = (
                    "🔴 ARKサーバーでエラーが発生しました！ログを確認してください。"
                )

            if message:
                await self.discord_bot.send_message(self.config.channel_id, message)
                logger.info(f"Sent status notification: {message}")

        except Exception as e:
            logger.error(f"Error sending status notification: {e}")
