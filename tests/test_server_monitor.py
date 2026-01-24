"""Tests for server monitoring functionality."""

import asyncio
from unittest.mock import AsyncMock, Mock

import pytest

from src.ark_discord_bot.server_monitor import MonitorConfig, ServerMonitor


class TestServerMonitor:
    """Tests for ServerMonitor class."""

    @pytest.fixture
    def server_monitor(self):
        """Create a ServerMonitor instance for testing."""
        mock_status_checker = Mock()
        mock_status_checker.get_server_status = AsyncMock()

        mock_discord_bot = Mock()
        mock_discord_bot.send_message = AsyncMock()

        config = MonitorConfig(
            channel_id=123456789,
            check_interval=1,  # 1 second for testing
        )

        return ServerMonitor(
            server_status_checker=mock_status_checker,
            discord_bot=mock_discord_bot,
            config=config,
        )

    @pytest.mark.asyncio
    async def test_start_monitoring(self, server_monitor):
        """Test starting the monitoring process."""
        server_monitor.server_status_checker.get_server_status = AsyncMock(
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
        server_monitor.server_status_checker.get_server_status = AsyncMock(
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
            server_monitor.config.channel_id,
            "🟢 ARKサーバーが接続準備完了しました！ 🦕",
        )
        assert server_monitor.last_status == "running"

    @pytest.mark.asyncio
    async def test_server_becomes_starting_notification(self, server_monitor):
        """Test notification when server transitions to starting state."""
        server_monitor.server_status_checker.get_server_status = AsyncMock(
            side_effect=["not_ready", "starting"]
        )
        server_monitor.discord_bot.send_message = AsyncMock()

        # First check - not_ready
        await server_monitor._check_server_status()
        assert server_monitor.last_status == "not_ready"

        # Second check - starting
        await server_monitor._check_server_status()

        # Verify notification was sent
        server_monitor.discord_bot.send_message.assert_called_with(
            server_monitor.config.channel_id,
            "🟡 ARKサーバーポッドが稼働中、ゲームサーバー起動中...",
        )
        assert server_monitor.last_status == "starting"

    @pytest.mark.asyncio
    async def test_server_becomes_not_ready_notification(self, server_monitor):
        """Test notification when server becomes not ready."""
        # Start with running status
        server_monitor.last_status = "running"

        server_monitor.server_status_checker.get_server_status = AsyncMock(
            return_value="not_ready"
        )
        server_monitor.discord_bot.send_message = AsyncMock()

        await server_monitor._check_server_status()

        # Verify notification was sent
        server_monitor.discord_bot.send_message.assert_called_with(
            server_monitor.config.channel_id,
            "🟡 ARKサーバーが再起動中または準備未完了です...",
        )
        assert server_monitor.last_status == "not_ready"

    @pytest.mark.asyncio
    async def test_server_error_notification(self, server_monitor):
        """Test notification when server has an error."""
        server_monitor.server_status_checker.get_server_status = AsyncMock(
            return_value="error"
        )
        server_monitor.discord_bot.send_message = AsyncMock()

        await server_monitor._check_server_status()

        # Verify error notification was sent
        server_monitor.discord_bot.send_message.assert_called_with(
            server_monitor.config.channel_id,
            "🔴 ARKサーバーでエラーが発生しました！ログを確認してください。",
        )
        assert server_monitor.last_status == "error"

    @pytest.mark.asyncio
    async def test_no_notification_when_status_unchanged(self, server_monitor):
        """Test no notification when status doesn't change."""
        server_monitor.last_status = "running"

        server_monitor.server_status_checker.get_server_status = AsyncMock(
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
        server_monitor.server_status_checker.get_server_status = AsyncMock(
            return_value="running"
        )

        status = await server_monitor.get_current_status()

        assert status == "running"
        server_monitor.server_status_checker.get_server_status.assert_called_once()

    @pytest.mark.asyncio
    async def test_monitoring_continues_after_exception(self, server_monitor):
        """Test monitoring continues after an exception."""
        server_monitor.server_status_checker.get_server_status = AsyncMock(
            side_effect=[Exception("Test error"), "running"]
        )
        server_monitor.discord_bot.send_message = AsyncMock()

        # First call should handle exception
        await server_monitor._check_server_status()

        # Second call should work normally
        await server_monitor._check_server_status()

        # Verify second call succeeded
        assert server_monitor.last_status == "running"

    @pytest.mark.asyncio
    async def test_transient_error_no_notification(self, server_monitor):
        """Test that transient errors do not trigger notifications."""
        # Start with running status
        server_monitor.last_status = "running"

        server_monitor.server_status_checker.get_server_status = AsyncMock(
            return_value="transient_error"
        )
        server_monitor.discord_bot.send_message = AsyncMock()

        await server_monitor._check_server_status()

        # Verify no notification was sent
        server_monitor.discord_bot.send_message.assert_not_called()
        # Verify status was not updated (maintained previous status)
        assert server_monitor.last_status == "running"

    @pytest.mark.asyncio
    async def test_transient_error_then_recovery(self, server_monitor):
        """Test status change notification after transient error recovery."""
        # Initial status is running
        server_monitor.last_status = "running"

        # First call returns transient_error
        server_monitor.server_status_checker.get_server_status = AsyncMock(
            side_effect=["transient_error", "not_ready", "running"]
        )
        server_monitor.discord_bot.send_message = AsyncMock()

        # First check - transient error (no notification, status maintained)
        await server_monitor._check_server_status()
        server_monitor.discord_bot.send_message.assert_not_called()
        assert server_monitor.last_status == "running"

        # Second check - status actually changes to not_ready (notification sent)
        await server_monitor._check_server_status()
        server_monitor.discord_bot.send_message.assert_called_once_with(
            server_monitor.config.channel_id,
            "🟡 ARKサーバーが再起動中または準備未完了です...",
        )
        assert server_monitor.last_status == "not_ready"

        # Third check - status changes to running (notification sent)
        await server_monitor._check_server_status()
        assert server_monitor.discord_bot.send_message.call_count == 2
        last_call = server_monitor.discord_bot.send_message.call_args_list[-1]
        assert last_call[0][1] == "🟢 ARKサーバーが接続準備完了しました！ 🦕"
        assert server_monitor.last_status == "running"


class TestServerMonitorDebounce:
    """Tests for ServerMonitor debounce functionality."""

    @pytest.fixture
    def server_monitor_with_debounce(self):
        """Create a ServerMonitor instance with debounce enabled."""
        mock_status_checker = Mock()
        mock_status_checker.get_server_status = AsyncMock()

        mock_discord_bot = Mock()
        mock_discord_bot.send_message = AsyncMock()

        config = MonitorConfig(
            channel_id=123456789,
            check_interval=1,
            failure_threshold=3,  # Require 3 consecutive failures before notification
        )

        return ServerMonitor(
            server_status_checker=mock_status_checker,
            discord_bot=mock_discord_bot,
            config=config,
        )

    @pytest.mark.asyncio
    async def test_single_failure_no_notification(self, server_monitor_with_debounce):
        """Test that a single failure from running does not trigger notification."""
        server_monitor = server_monitor_with_debounce
        server_monitor.last_status = "running"

        server_monitor.server_status_checker.get_server_status = AsyncMock(
            return_value="starting"
        )

        # Single failure should not trigger notification
        await server_monitor._check_server_status()

        server_monitor.discord_bot.send_message.assert_not_called()
        # Internal status should still be running (not updated until threshold reached)
        assert server_monitor.last_status == "running"
        assert server_monitor._failure_count == 1

    @pytest.mark.asyncio
    async def test_two_failures_no_notification(self, server_monitor_with_debounce):
        """Test that two consecutive failures do not trigger notification."""
        server_monitor = server_monitor_with_debounce
        server_monitor.last_status = "running"

        server_monitor.server_status_checker.get_server_status = AsyncMock(
            return_value="starting"
        )

        # Two failures should not trigger notification
        await server_monitor._check_server_status()
        await server_monitor._check_server_status()

        server_monitor.discord_bot.send_message.assert_not_called()
        assert server_monitor.last_status == "running"
        assert server_monitor._failure_count == 2

    @pytest.mark.asyncio
    async def test_three_failures_triggers_notification(
        self, server_monitor_with_debounce
    ):
        """Test that three consecutive failures trigger notification."""
        server_monitor = server_monitor_with_debounce
        server_monitor.last_status = "running"

        server_monitor.server_status_checker.get_server_status = AsyncMock(
            return_value="starting"
        )

        # Three failures should trigger notification
        await server_monitor._check_server_status()
        await server_monitor._check_server_status()
        await server_monitor._check_server_status()

        server_monitor.discord_bot.send_message.assert_called_once_with(
            server_monitor.config.channel_id,
            "🟡 ARKサーバーが再起動中または準備未完了です...",
        )
        assert server_monitor.last_status == "starting"
        assert server_monitor._failure_count == 0  # Reset after notification

    @pytest.mark.asyncio
    async def test_recovery_resets_failure_count(self, server_monitor_with_debounce):
        """Test that recovery to running resets the failure counter."""
        server_monitor = server_monitor_with_debounce
        server_monitor.last_status = "running"

        # Two failures then recovery
        server_monitor.server_status_checker.get_server_status = AsyncMock(
            side_effect=["starting", "starting", "running"]
        )

        await server_monitor._check_server_status()
        assert server_monitor._failure_count == 1

        await server_monitor._check_server_status()
        assert server_monitor._failure_count == 2

        # Recovery should reset counter and not send any notification
        await server_monitor._check_server_status()
        server_monitor.discord_bot.send_message.assert_not_called()
        assert server_monitor._failure_count == 0
        assert server_monitor.last_status == "running"

    @pytest.mark.asyncio
    async def test_recovery_from_degraded_state_sends_notification(
        self, server_monitor_with_debounce
    ):
        """Test that recovery from degraded state sends notification immediately."""
        server_monitor = server_monitor_with_debounce
        # Simulate state where server was already in degraded state
        server_monitor.last_status = "starting"
        server_monitor._failure_count = 0

        server_monitor.server_status_checker.get_server_status = AsyncMock(
            return_value="running"
        )

        await server_monitor._check_server_status()

        # Recovery notification should be sent immediately (no debounce)
        server_monitor.discord_bot.send_message.assert_called_once_with(
            server_monitor.config.channel_id,
            "🟢 ARKサーバーが接続準備完了しました！ 🦕",
        )
        assert server_monitor.last_status == "running"

    @pytest.mark.asyncio
    async def test_error_state_triggers_immediate_notification(
        self, server_monitor_with_debounce
    ):
        """Test that error state triggers notification immediately without debounce."""
        server_monitor = server_monitor_with_debounce
        server_monitor.last_status = "running"

        server_monitor.server_status_checker.get_server_status = AsyncMock(
            return_value="error"
        )

        # Error should trigger immediate notification
        await server_monitor._check_server_status()

        server_monitor.discord_bot.send_message.assert_called_once_with(
            server_monitor.config.channel_id,
            "🔴 ARKサーバーでエラーが発生しました！ログを確認してください。",
        )
        assert server_monitor.last_status == "error"

    @pytest.mark.asyncio
    async def test_not_ready_also_debounced(self, server_monitor_with_debounce):
        """Test that not_ready status is also debounced."""
        server_monitor = server_monitor_with_debounce
        server_monitor.last_status = "running"

        server_monitor.server_status_checker.get_server_status = AsyncMock(
            return_value="not_ready"
        )

        # First two failures - no notification
        await server_monitor._check_server_status()
        await server_monitor._check_server_status()
        server_monitor.discord_bot.send_message.assert_not_called()

        # Third failure - notification sent
        await server_monitor._check_server_status()
        server_monitor.discord_bot.send_message.assert_called_once_with(
            server_monitor.config.channel_id,
            "🟡 ARKサーバーが再起動中または準備未完了です...",
        )

    @pytest.mark.asyncio
    async def test_default_failure_threshold_is_one(self):
        """Test that default failure threshold is 1 (backward compatible)."""
        mock_status_checker = Mock()
        mock_status_checker.get_server_status = AsyncMock(return_value="starting")

        mock_discord_bot = Mock()
        mock_discord_bot.send_message = AsyncMock()

        # Create without specifying failure_threshold (should default to 1)
        config = MonitorConfig(channel_id=123456789, check_interval=1)
        server_monitor = ServerMonitor(
            server_status_checker=mock_status_checker,
            discord_bot=mock_discord_bot,
            config=config,
        )
        server_monitor.last_status = "running"

        # With default threshold of 1, first failure should trigger notification
        await server_monitor._check_server_status()

        mock_discord_bot.send_message.assert_called_once()

    @pytest.mark.asyncio
    async def test_mixed_failure_types_count_together(
        self, server_monitor_with_debounce
    ):
        """Test that different non-running states count toward same failure counter."""
        server_monitor = server_monitor_with_debounce
        server_monitor.last_status = "running"

        # Mix of starting and not_ready should count together
        server_monitor.server_status_checker.get_server_status = AsyncMock(
            side_effect=["starting", "not_ready", "starting"]
        )

        await server_monitor._check_server_status()
        assert server_monitor._failure_count == 1

        await server_monitor._check_server_status()
        assert server_monitor._failure_count == 2

        await server_monitor._check_server_status()
        # Third failure triggers notification
        server_monitor.discord_bot.send_message.assert_called_once()
