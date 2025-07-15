"""Server status checker for ARK server using RCON connectivity."""

import asyncio
import logging
from typing import Optional

from .kubernetes_manager import KubernetesManager
from .rcon_manager import RconManager

logger = logging.getLogger(__name__)


class ServerStatusChecker:
    """Checks actual ARK server status using RCON connectivity."""

    def __init__(
        self,
        kubernetes_manager: KubernetesManager,
        rcon_manager: RconManager,
        rcon_timeout: int = 5,
    ):
        """Initialize ServerStatusChecker.

        Args:
            kubernetes_manager: Kubernetes manager instance
            rcon_manager: RCON manager instance
            rcon_timeout: Timeout for RCON connection attempts in seconds
        """
        self.kubernetes_manager = kubernetes_manager
        self.rcon_manager = rcon_manager
        self.rcon_timeout = rcon_timeout

    async def get_server_status(self) -> str:
        """Get the current status of the ARK server using comprehensive checks.

        First checks Kubernetes deployment status, then verifies actual RCON connectivity
        to ensure the server is actually ready for players.

        Returns:
            str: Status of the server
                - 'running': Server is running and RCON accessible
                - 'starting': K8s pods are running but RCON not yet accessible
                - 'not_ready': K8s deployment not ready
                - 'error': Error occurred during status check
        """
        try:
            # First check Kubernetes deployment status
            k8s_status = await self.kubernetes_manager.get_server_status()

            if k8s_status == "error":
                return "error"
            elif k8s_status == "not_ready":
                return "not_ready"

            # If K8s deployment is running, check actual RCON connectivity
            rcon_accessible = await self._check_rcon_connectivity()

            if rcon_accessible:
                return "running"
            else:
                # Pods are running but RCON not accessible yet (server still starting)
                return "starting"

        except Exception as e:
            logger.error(f"Unexpected error getting server status: {e}")
            return "error"

    async def _check_rcon_connectivity(self) -> bool:
        """Check if RCON is accessible by attempting a simple command.

        Returns:
            bool: True if RCON is accessible, False otherwise
        """
        try:
            # Use asyncio.wait_for to add timeout to RCON connection attempt
            result = await asyncio.wait_for(
                self.rcon_manager.send_command("echo test"), timeout=self.rcon_timeout
            )

            # If we get any response (even if it's None), RCON is at least accessible
            # The important thing is that the connection attempt didn't timeout or fail
            return result is not None

        except asyncio.TimeoutError:
            logger.debug("RCON connection timed out - server likely still starting")
            return False
        except Exception as e:
            logger.debug(f"RCON connectivity check failed: {e}")
            return False

    async def wait_for_server_ready(
        self, max_wait_time: int = 300, check_interval: int = 10
    ) -> bool:
        """Wait for server to become ready (RCON accessible).

        Args:
            max_wait_time: Maximum time to wait in seconds
            check_interval: Interval between checks in seconds

        Returns:
            bool: True if server became ready, False if timeout
        """
        start_time = asyncio.get_event_loop().time()

        while True:
            current_time = asyncio.get_event_loop().time()
            if current_time - start_time > max_wait_time:
                logger.warning(
                    f"Server did not become ready within {max_wait_time} seconds"
                )
                return False

            status = await self.get_server_status()
            if status == "running":
                logger.info("Server is now ready and RCON accessible")
                return True
            elif status == "error":
                logger.error("Server status check returned error")
                return False

            logger.debug(
                f"Server status: {status}, waiting {check_interval}s before next check"
            )
            await asyncio.sleep(check_interval)
