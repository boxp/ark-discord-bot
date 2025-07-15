"""Tests for RCON manager functionality."""

import pytest
from unittest.mock import Mock, patch, AsyncMock
import asyncio

from src.ark_discord_bot.rcon_manager import RconManager


class TestRconManager:
    """Tests for RconManager class."""

    @pytest.fixture
    def rcon_manager(self):
        """Create an RconManager instance for testing."""
        return RconManager(
            host="192.168.10.29",
            port=27020,
            password="test_password"
        )

    @pytest.mark.asyncio
    async def test_get_online_players_success(self, rcon_manager):
        """Test successful retrieval of online players."""
        mock_response = "Player1, Player2, Player3"

        with patch('src.ark_discord_bot.rcon_manager.asyncio.open_connection') as mock_open_connection:
            mock_reader = Mock()
            mock_writer = Mock()
            mock_open_connection.return_value = (mock_reader, mock_writer)

            # Mock authentication
            mock_reader.read.side_effect = [
                b'\x0a\x00\x00\x00\x00\x00\x00\x00\x02\x00\x00\x00\x00\x00',  # Auth response
                b'\x20\x00\x00\x00\x00\x00\x00\x00\x00\x00\x00\x00Player1, Player2, Player3\x00\x00'  # Command response
            ]

            players = await rcon_manager.get_online_players()

            assert players == ["Player1", "Player2", "Player3"]
            mock_writer.write.assert_called()
            mock_writer.close.assert_called()

    @pytest.mark.asyncio
    async def test_get_online_players_no_players(self, rcon_manager):
        """Test retrieval when no players are online."""
        with patch('src.ark_discord_bot.rcon_manager.asyncio.open_connection') as mock_open_connection:
            mock_reader = Mock()
            mock_writer = Mock()
            mock_open_connection.return_value = (mock_reader, mock_writer)

            # Mock authentication and empty response
            mock_reader.read.side_effect = [
                b'\x0a\x00\x00\x00\x00\x00\x00\x00\x02\x00\x00\x00\x00\x00',  # Auth response
                b'\x0a\x00\x00\x00\x00\x00\x00\x00\x00\x00\x00\x00\x00\x00'  # Empty command response
            ]

            players = await rcon_manager.get_online_players()

            assert players == []

    @pytest.mark.asyncio
    async def test_get_online_players_connection_error(self, rcon_manager):
        """Test retrieval when connection error occurs."""
        with patch('src.ark_discord_bot.rcon_manager.asyncio.open_connection') as mock_open_connection:
            mock_open_connection.side_effect = ConnectionError("Connection refused")

            players = await rcon_manager.get_online_players()

            assert players == []

    @pytest.mark.asyncio
    async def test_get_online_players_auth_failure(self, rcon_manager):
        """Test retrieval when authentication fails."""
        with patch('src.ark_discord_bot.rcon_manager.asyncio.open_connection') as mock_open_connection:
            mock_reader = Mock()
            mock_writer = Mock()
            mock_open_connection.return_value = (mock_reader, mock_writer)

            # Mock authentication failure
            mock_reader.read.side_effect = [
                b'\x0a\x00\x00\x00\xff\xff\xff\xff\x02\x00\x00\x00\x00\x00'  # Auth failure response
            ]

            players = await rcon_manager.get_online_players()

            assert players == []

    @pytest.mark.asyncio
    async def test_send_command_success(self, rcon_manager):
        """Test successful command execution."""
        with patch('src.ark_discord_bot.rcon_manager.asyncio.open_connection') as mock_open_connection:
            mock_reader = Mock()
            mock_writer = Mock()
            mock_open_connection.return_value = (mock_reader, mock_writer)

            # Mock authentication and command response
            mock_reader.read.side_effect = [
                b'\x0a\x00\x00\x00\x00\x00\x00\x00\x02\x00\x00\x00\x00\x00',  # Auth response
                b'\x20\x00\x00\x00\x00\x00\x00\x00\x00\x00\x00\x00Command executed\x00\x00'  # Command response
            ]

            result = await rcon_manager.send_command("broadcast Hello")

            assert result == "Command executed"

    @pytest.mark.asyncio
    async def test_send_command_failure(self, rcon_manager):
        """Test command execution failure."""
        with patch('src.ark_discord_bot.rcon_manager.asyncio.open_connection') as mock_open_connection:
            mock_open_connection.side_effect = Exception("Connection error")

            result = await rcon_manager.send_command("broadcast Hello")

            assert result is None
