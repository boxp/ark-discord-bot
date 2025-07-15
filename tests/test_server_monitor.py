"""Tests for server monitoring functionality."""

import pytest
from unittest.mock import Mock, patch, AsyncMock
import asyncio

from src.ark_discord_bot.server_monitor import ServerMonitor


class TestServerMonitor:
    """Tests for ServerMonitor class."""

    @pytest.fixture
    def server_monitor(self):
        """Create a ServerMonitor instance for testing."""
        mock_k8s_manager = Mock()
        mock_discord_bot = Mock()
        return ServerMonitor(
            kubernetes_manager=mock_k8s_manager,
            discord_bot=mock_discord_bot,
            channel_id=123456789,
            check_interval=1  # 1 second for testing
        )

    @pytest.mark.asyncio
    async def test_start_monitoring(self, server_monitor):
        """Test starting the monitoring process."""
        server_monitor.kubernetes_manager.get_server_status = AsyncMock(
            side_effect=["not_ready", "running", "running"]
        )
        server_monitor.discord_bot.send_message = AsyncMock()
        
        # Start monitoring and let it run for a short time
        monitoring_task = asyncio.create_task(server_monitor.start_monitoring())
        await asyncio.sleep(0.1)  # Let it run briefly
        monitoring_task.cancel()
        
        try:
            await monitoring_task
        except asyncio.CancelledError:
            pass
        
        # Verify monitoring was started
        assert server_monitor.is_monitoring is True

    @pytest.mark.asyncio
    async def test_server_becomes_ready_notification(self, server_monitor):
        """Test notification when server becomes ready."""
        server_monitor.kubernetes_manager.get_server_status = AsyncMock(
            side_effect=["not_ready", "running"]
        )
        server_monitor.discord_bot.send_message = AsyncMock()
        
        # Simulate status check
        await server_monitor._check_server_status()
        
        # Server should now be marked as running
        assert server_monitor.last_status == "not_ready"
        
        # Check again to trigger notification
        await server_monitor._check_server_status()
        
        # Verify notification was sent
        server_monitor.discord_bot.send_message.assert_called_with(
            server_monitor.channel_id,
            "🟢 ARK Server is now ready for connections! 🦕"
        )
        assert server_monitor.last_status == "running"

    @pytest.mark.asyncio
    async def test_server_becomes_not_ready_notification(self, server_monitor):
        """Test notification when server becomes not ready."""
        # Start with running status
        server_monitor.last_status = "running"
        
        server_monitor.kubernetes_manager.get_server_status = AsyncMock(
            return_value="not_ready"
        )
        server_monitor.discord_bot.send_message = AsyncMock()
        
        await server_monitor._check_server_status()
        
        # Verify notification was sent
        server_monitor.discord_bot.send_message.assert_called_with(
            server_monitor.channel_id,
            "🟡 ARK Server is restarting or not ready..."
        )
        assert server_monitor.last_status == "not_ready"

    @pytest.mark.asyncio
    async def test_server_error_notification(self, server_monitor):
        """Test notification when server has an error."""
        server_monitor.kubernetes_manager.get_server_status = AsyncMock(
            return_value="error"
        )
        server_monitor.discord_bot.send_message = AsyncMock()
        
        await server_monitor._check_server_status()
        
        # Verify error notification was sent
        server_monitor.discord_bot.send_message.assert_called_with(
            server_monitor.channel_id,
            "🔴 ARK Server encountered an error! Please check the logs."
        )
        assert server_monitor.last_status == "error"

    @pytest.mark.asyncio
    async def test_no_notification_when_status_unchanged(self, server_monitor):
        """Test no notification when status doesn't change."""
        server_monitor.last_status = "running"
        
        server_monitor.kubernetes_manager.get_server_status = AsyncMock(
            return_value="running"
        )
        server_monitor.discord_bot.send_message = AsyncMock()
        
        await server_monitor._check_server_status()
        
        # Verify no notification was sent
        server_monitor.discord_bot.send_message.assert_not_called()
        assert server_monitor.last_status == "running"

    def test_stop_monitoring(self, server_monitor):
        """Test stopping the monitoring process."""
        server_monitor.is_monitoring = True
        server_monitor.stop_monitoring()
        
        assert server_monitor.is_monitoring is False

    @pytest.mark.asyncio
    async def test_get_current_status(self, server_monitor):
        """Test getting current server status."""
        server_monitor.kubernetes_manager.get_server_status = AsyncMock(
            return_value="running"
        )
        
        status = await server_monitor.get_current_status()
        
        assert status == "running"
        server_monitor.kubernetes_manager.get_server_status.assert_called_once()

    @pytest.mark.asyncio
    async def test_monitoring_continues_after_exception(self, server_monitor):
        """Test monitoring continues after an exception."""
        server_monitor.kubernetes_manager.get_server_status = AsyncMock(
            side_effect=[Exception("Test error"), "running"]
        )
        server_monitor.discord_bot.send_message = AsyncMock()
        
        # First call should handle exception
        await server_monitor._check_server_status()
        
        # Second call should work normally
        await server_monitor._check_server_status()
        
        # Verify second call succeeded
        assert server_monitor.last_status == "running"