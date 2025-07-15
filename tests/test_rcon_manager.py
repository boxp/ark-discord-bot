"""Tests for RCON manager functionality."""

import asyncio
from unittest.mock import AsyncMock, Mock, patch

import pytest

from src.ark_discord_bot.rcon_manager import RconManager


class TestRconManager:
    """Tests for RconManager class."""

    @pytest.fixture
    def rcon_manager(self):
        """Create an RconManager instance for testing."""
        return RconManager(host="192.168.10.29", port=27020, password="test_password")

    @pytest.mark.asyncio
    async def test_get_online_players_success(self, rcon_manager):
        """Test successful retrieval of online players."""
        with patch.object(
            rcon_manager,
            "send_command",
            return_value="Player1, 123456\nPlayer2, 789012\nPlayer3, 345678",
        ):
            players = await rcon_manager.get_online_players()
            assert players == ["Player1", "Player2", "Player3"]

    @pytest.mark.asyncio
    async def test_get_online_players_no_players(self, rcon_manager):
        """Test retrieval when no players are online."""
        with patch(
            "src.ark_discord_bot.rcon_manager.asyncio.open_connection"
        ) as mock_open_connection:
            mock_reader = Mock()
            mock_writer = Mock()

            # Mock writer methods properly
            mock_writer.write = Mock()
            mock_writer.drain = AsyncMock()
            mock_writer.close = Mock()
            mock_writer.wait_closed = AsyncMock()

            # Mock reader read method as async
            mock_reader.read = AsyncMock()
            mock_reader.read.side_effect = [
                b"\x0a\x00\x00\x00",  # Size for auth response (10 bytes)
                b"\x01\x00\x00\x00\x02\x00\x00\x00\x00\x00",  # Auth response data (id=1, type=2, empty data)
                b"\x0a\x00\x00\x00",  # Size for command response (10 bytes)
                b"\x01\x00\x00\x00\x00\x00\x00\x00\x00\x00",  # Empty command response data (id=1, type=0, empty)
            ]

            mock_open_connection.return_value = (mock_reader, mock_writer)

            players = await rcon_manager.get_online_players()

            assert players == []

    @pytest.mark.asyncio
    async def test_get_online_players_connection_error(self, rcon_manager):
        """Test retrieval when connection error occurs."""
        with patch(
            "src.ark_discord_bot.rcon_manager.asyncio.open_connection"
        ) as mock_open_connection:
            mock_open_connection.side_effect = ConnectionError("Connection refused")

            players = await rcon_manager.get_online_players()

            assert players == []

    @pytest.mark.asyncio
    async def test_get_online_players_auth_failure(self, rcon_manager):
        """Test retrieval when authentication fails."""
        with patch(
            "src.ark_discord_bot.rcon_manager.asyncio.open_connection"
        ) as mock_open_connection:
            mock_reader = Mock()
            mock_writer = Mock()

            # Mock writer methods properly
            mock_writer.write = Mock()
            mock_writer.drain = AsyncMock()
            mock_writer.close = Mock()
            mock_writer.wait_closed = AsyncMock()

            # Mock reader read method as async
            mock_reader.read = AsyncMock()
            mock_reader.read.side_effect = [
                b"\x0a\x00\x00\x00",  # Size for auth response (10 bytes)
                b"\xff\xff\xff\xff\x02\x00\x00\x00\x00\x00",  # Auth failure response (id=-1, type=2, empty data)
            ]

            mock_open_connection.return_value = (mock_reader, mock_writer)

            players = await rcon_manager.get_online_players()

            assert players == []

    @pytest.mark.asyncio
    async def test_send_command_success(self, rcon_manager):
        """Test successful command execution."""
        with patch(
            "src.ark_discord_bot.rcon_manager.asyncio.open_connection"
        ) as mock_open_connection:
            mock_reader = Mock()
            mock_writer = Mock()

            # Mock writer methods properly
            mock_writer.write = Mock()
            mock_writer.drain = AsyncMock()
            mock_writer.close = Mock()
            mock_writer.wait_closed = AsyncMock()

            # Mock reader read method as async
            mock_reader.read = AsyncMock()
            mock_reader.read.side_effect = [
                b"\x0a\x00\x00\x00",  # Size for auth response (10 bytes)
                b"\x01\x00\x00\x00\x02\x00\x00\x00\x00\x00",  # Auth response data (id=1, type=2, empty data)
                b"\x1a\x00\x00\x00",  # Size for command response (26 bytes)
                b"\x01\x00\x00\x00\x00\x00\x00\x00Command executed\x00\x00",  # Command response data (id=1, type=0, data)
            ]

            mock_open_connection.return_value = (mock_reader, mock_writer)

            result = await rcon_manager.send_command("broadcast Hello")

            assert result == "Command executed"

    @pytest.mark.asyncio
    async def test_send_command_failure(self, rcon_manager):
        """Test command execution failure."""
        with patch(
            "src.ark_discord_bot.rcon_manager.asyncio.open_connection"
        ) as mock_open_connection:
            mock_open_connection.side_effect = Exception("Connection error")

            result = await rcon_manager.send_command("broadcast Hello")

            assert result is None
