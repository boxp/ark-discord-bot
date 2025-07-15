"""Tests for server status checker functionality."""

import asyncio
from unittest.mock import AsyncMock, Mock, patch

import pytest

from src.ark_discord_bot.server_status_checker import ServerStatusChecker


class TestServerStatusChecker:
    """Tests for ServerStatusChecker class."""

    @pytest.fixture
    def mock_kubernetes_manager(self):
        """Create mock Kubernetes manager."""
        mock = Mock()
        mock.get_server_status = AsyncMock()
        return mock

    @pytest.fixture
    def mock_rcon_manager(self):
        """Create mock RCON manager."""
        mock = Mock()
        mock.send_command = AsyncMock()
        return mock

    @pytest.fixture
    def server_status_checker(self, mock_kubernetes_manager, mock_rcon_manager):
        """Create ServerStatusChecker instance for testing."""
        return ServerStatusChecker(
            kubernetes_manager=mock_kubernetes_manager,
            rcon_manager=mock_rcon_manager,
            rcon_timeout=1,  # Short timeout for tests
        )

    @pytest.mark.asyncio
    async def test_get_server_status_running(
        self, server_status_checker, mock_kubernetes_manager, mock_rcon_manager
    ):
        """Test server status when both K8s and RCON are ready."""
        mock_kubernetes_manager.get_server_status.return_value = "running"
        mock_rcon_manager.send_command.return_value = "test"

        status = await server_status_checker.get_server_status()

        assert status == "running"
        mock_kubernetes_manager.get_server_status.assert_called_once()
        mock_rcon_manager.send_command.assert_called_once_with("echo test")

    @pytest.mark.asyncio
    async def test_get_server_status_starting(
        self, server_status_checker, mock_kubernetes_manager, mock_rcon_manager
    ):
        """Test server status when K8s is running but RCON not accessible."""
        mock_kubernetes_manager.get_server_status.return_value = "running"
        mock_rcon_manager.send_command.return_value = None

        status = await server_status_checker.get_server_status()

        assert status == "starting"
        mock_kubernetes_manager.get_server_status.assert_called_once()
        mock_rcon_manager.send_command.assert_called_once_with("echo test")

    @pytest.mark.asyncio
    async def test_get_server_status_not_ready(
        self, server_status_checker, mock_kubernetes_manager, mock_rcon_manager
    ):
        """Test server status when K8s deployment is not ready."""
        mock_kubernetes_manager.get_server_status.return_value = "not_ready"

        status = await server_status_checker.get_server_status()

        assert status == "not_ready"
        mock_kubernetes_manager.get_server_status.assert_called_once()
        # RCON should not be checked if K8s is not ready
        mock_rcon_manager.send_command.assert_not_called()

    @pytest.mark.asyncio
    async def test_get_server_status_error(
        self, server_status_checker, mock_kubernetes_manager, mock_rcon_manager
    ):
        """Test server status when K8s returns error."""
        mock_kubernetes_manager.get_server_status.return_value = "error"

        status = await server_status_checker.get_server_status()

        assert status == "error"
        mock_kubernetes_manager.get_server_status.assert_called_once()
        # RCON should not be checked if K8s has error
        mock_rcon_manager.send_command.assert_not_called()

    @pytest.mark.asyncio
    async def test_get_server_status_rcon_timeout(
        self, server_status_checker, mock_kubernetes_manager, mock_rcon_manager
    ):
        """Test server status when RCON times out."""
        mock_kubernetes_manager.get_server_status.return_value = "running"

        # Mock RCON to take longer than timeout
        async def slow_rcon_command(command):
            await asyncio.sleep(2)  # Longer than 1 second timeout
            return "test"

        mock_rcon_manager.send_command.side_effect = slow_rcon_command

        status = await server_status_checker.get_server_status()

        assert status == "starting"

    @pytest.mark.asyncio
    async def test_get_server_status_rcon_exception(
        self, server_status_checker, mock_kubernetes_manager, mock_rcon_manager
    ):
        """Test server status when RCON throws exception."""
        mock_kubernetes_manager.get_server_status.return_value = "running"
        mock_rcon_manager.send_command.side_effect = Exception("RCON error")

        status = await server_status_checker.get_server_status()

        assert status == "starting"

    @pytest.mark.asyncio
    async def test_check_rcon_connectivity_success(
        self, server_status_checker, mock_rcon_manager
    ):
        """Test successful RCON connectivity check."""
        mock_rcon_manager.send_command.return_value = "test response"

        result = await server_status_checker._check_rcon_connectivity()

        assert result is True
        mock_rcon_manager.send_command.assert_called_once_with("echo test")

    @pytest.mark.asyncio
    async def test_check_rcon_connectivity_failure(
        self, server_status_checker, mock_rcon_manager
    ):
        """Test failed RCON connectivity check."""
        mock_rcon_manager.send_command.return_value = None

        result = await server_status_checker._check_rcon_connectivity()

        assert result is False

    @pytest.mark.asyncio
    async def test_wait_for_server_ready_success(
        self, server_status_checker, mock_kubernetes_manager, mock_rcon_manager
    ):
        """Test waiting for server to become ready - success case."""
        # First call returns "starting", second returns "running"
        mock_kubernetes_manager.get_server_status.return_value = "running"
        mock_rcon_manager.send_command.side_effect = [None, "test"]

        result = await server_status_checker.wait_for_server_ready(
            max_wait_time=5, check_interval=1
        )

        assert result is True
        assert mock_rcon_manager.send_command.call_count == 2

    @pytest.mark.asyncio
    async def test_wait_for_server_ready_timeout(
        self, server_status_checker, mock_kubernetes_manager, mock_rcon_manager
    ):
        """Test waiting for server to become ready - timeout case."""
        mock_kubernetes_manager.get_server_status.return_value = "running"
        mock_rcon_manager.send_command.return_value = None  # Always not ready

        result = await server_status_checker.wait_for_server_ready(
            max_wait_time=2, check_interval=1
        )

        assert result is False

    @pytest.mark.asyncio
    async def test_wait_for_server_ready_error(
        self, server_status_checker, mock_kubernetes_manager, mock_rcon_manager
    ):
        """Test waiting for server to become ready - error case."""
        mock_kubernetes_manager.get_server_status.return_value = "error"

        result = await server_status_checker.wait_for_server_ready(
            max_wait_time=5, check_interval=1
        )

        assert result is False
        # Should stop immediately on error
        mock_kubernetes_manager.get_server_status.assert_called_once()
